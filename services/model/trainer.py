import joblib
import numpy as np
from datetime import datetime
from pathlib import Path
from sklearn.ensemble import RandomForestClassifier
from features import FeatureEngineering
from scoring import TradingScorer
from utils.logger import get_logger
from sklearn.model_selection import TimeSeriesSplit
import optuna

logger = get_logger(__name__)

class ModelTrainer:
	"""Train and save ML models."""

	def __init__(self, config):
		self.config = config
		self.model = None
		self.best_optuna_params = None

	def _create_model(self):
		return RandomForestClassifier(
			n_estimators=self.config.n_estimators,
			random_state=self.config.random_state,
		)

	def train(self, events_dict, prices_dict, asset_names):
		"""
		Optuna-based time-series optimization. Tunes lambdaDenom, l_threshold, s_threshold,
		uses TimeSeriesSplit CV and leaves the last split for final test evaluation.
		"""
		scorer = TradingScorer.get_scorer()

		# Optuna search space bounds (match notebook)
		lambda_low, lambda_high = 60.0, 2592000.0
		l_thresh_low, l_thresh_high = 0.005, 0.05
		s_thresh_low, s_thresh_high = 0.005, 0.05

		# get number of trials from config or default
		n_trials = getattr(self.config, "optuna_trials", 100)

		# Define objective for Optuna
		def objective(trial):
			params = {
				"lambdaDenom": trial.suggest_float("lambdaDenom", lambda_low, lambda_high, log=True),
				"l_threshold": trial.suggest_float("l_threshold", l_thresh_low, l_thresh_high),
				"s_threshold": trial.suggest_float("s_threshold", s_thresh_low, s_thresh_high),
			}
			try:
				X, y = FeatureEngineering.prepare_features(
					events_dict, prices_dict, asset_names,
					params["lambdaDenom"],
					params["l_threshold"],
					params["s_threshold"]
				)
				if X is None or y is None or X.size == 0 or y.size == 0:
					return -np.inf
				if len(X) < 100:
					return -np.inf
				if not np.isfinite(X).all():
					return -np.inf

				# require reasonable class counts (at least some samples per trade class)
				class_counts = np.bincount(y)
				if len(class_counts) < 2 or np.min(class_counts) < 5:
					return -np.inf

				kfold = TimeSeriesSplit(n_splits=5)
				splits = list(kfold.split(X))
				if len(splits) < 2:
					return -np.inf
				# use all but last split for CV
				cv_splits = splits[:-1]

				scores = []
				for train_idx, val_idx in cv_splits:
					# create fresh model per fold
					mod = RandomForestClassifier(
						n_estimators=self.config.n_estimators,
						random_state=self.config.random_state
					)
					mod.fit(X[train_idx], y[train_idx])
					y_pred_cv = mod.predict(X[val_idx])
					fold_score = scorer(y[val_idx], y_pred_cv)
					if not np.isfinite(fold_score):
						return -np.inf
					scores.append(fold_score)

				mean_score = np.mean(scores)
				trial.set_user_attr("n_samples", len(X))
				trial.set_user_attr("class_counts", class_counts.tolist())
				trial.set_user_attr("std_score", float(np.std(scores)))
				return mean_score

			except Exception as e:
				logger.debug(f"Trial {trial.number} failed: {type(e).__name__}: {e}")
				return -np.inf

		# Run optimization
		study = optuna.create_study(
			direction="maximize",
			sampler=optuna.samplers.TPESampler(seed=self.config.random_state)
		)
		try:
			study.optimize(objective, n_trials=n_trials)
		except Exception as e:
			logger.error(f"Optuna optimization failed: {e}")
			return {"success": False, "error": "Optuna optimization failed"}

		if len(study.trials) == 0 or study.best_value == -np.inf:
			return {"success": False, "error": "No successful optuna trials"}

		best_params = study.best_params
		self.best_optuna_params = best_params
		logger.info(f"Optuna best value: {study.best_value:.4f} params: {best_params}")

		# Prepare final dataset with best params and train final model on final train/test split
		try:
			X, y = FeatureEngineering.prepare_features(
				events_dict, prices_dict, asset_names,
				best_params["lambdaDenom"],
				best_params["l_threshold"],
				best_params["s_threshold"]
			)
			if X is None or y is None or X.size == 0 or y.size == 0 or not np.isfinite(X).all():
				return {"success": False, "error": "Final feature preparation produced invalid data"}
		except Exception as e:
			logger.error(f"Final feature preparation failed: {type(e).__name__}: {e}")
			return {"success": False, "error": "Final feature preparation failed"}


		# Final train/test using last TimeSeriesSplit fold
		kfold = TimeSeriesSplit(n_splits=5)
		splits = list(kfold.split(X))
		train_idx, test_idx = splits[-1]
		X_train, y_train = X[train_idx], y[train_idx]
		X_test, y_test = X[test_idx], y[test_idx]

		final_model = self._create_model()
		final_model.fit(X_train, y_train)
		y_pred_test = final_model.predict(X_test)
		test_score = scorer(y_test, y_pred_test)

		self.model = final_model

		logger.info(f"Final test masked F1 score: {test_score:.4f}")

		return {
			"success": True,
			"testScore": float(test_score),
			"bestParams": {
				"lambdaDenom": float(best_params["lambdaDenom"]),
				"longThreshold": float(best_params["l_threshold"]),
				"shortThreshold": float(best_params["s_threshold"]),
			},
			"optunaBestValue": float(study.best_value),
			"timestamp": int(datetime.now().timestamp() * 1000)
		}

	def predict(self, events_dict, prices_dict, asset_names):
		"""Make predictions using the trained model."""
		if self.model is None:
			logger.warning("No model trained yet. Cannot make predictions.")
			return None
		
		X, _ = FeatureEngineering.prepare_features(
			events_dict, prices_dict, asset_names,
			self.best_optuna_params["lambdaDenom"],
			self.best_optuna_params["l_threshold"],
			self.best_optuna_params["s_threshold"]
		)
		if X is None or X.size == 0 or not np.isfinite(X).all():
			logger.warning("Prepared features are invalid. Cannot make predictions.")
			return None
		
		predictions = self.model.predict(X)


		return {
			"predictions": predictions.tolist(),
			""
			"timestamp": datetime.now().isoformat()
		}
	
	def get_hyperparameters(self) -> dict:
		"""Get both model parameters and optimized Optuna parameters."""
		if self.model is None:
			return None
		
		params = {
			"model_params": self.model.get_params(),
			"optuna_params": self.best_optuna_params
		}
		return params
	
	def save(self, output_dir):
		if not self.model:
			logger.warning("No model to save.")
			return
		path = Path(output_dir)
		path.mkdir(parents=True, exist_ok=True)
		fname = path / f"model_{datetime.now().strftime('%Y%m%d_%H%M%S')}.pkl"
		joblib.dump(self.model, fname)
		logger.info(f"Model saved at {fname}")
