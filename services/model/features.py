from typing import Tuple
import numpy as np
import pandas as pd
from utils.logger import get_logger
from typing import Dict, List

logger = get_logger(__name__)

class FeatureEngineering:
    """Feature engineering pipeline."""

    @staticmethod
    def inject_blank_events(events_df: pd.DataFrame, freq: str) -> pd.DataFrame:
        events_df = events_df.copy()
        events_df["event_dt"] = pd.to_datetime(events_df["dt"])
        events_df["dt"] = pd.to_datetime(events_df["dt"]).dt.floor(freq=freq)
        events_df["is_blank"] = False
        events_df = events_df.drop_duplicates("dt", keep="first")
        
        # Get complete date range
        all_dts = pd.date_range(events_df["dt"].min(), events_df["dt"].max(), freq=freq)
        
        # Find ONLY missing dates
        existing_dts = set(events_df["dt"])
        missing_dts = all_dts[~all_dts.isin(existing_dts)]
        
        # Create blanks ONLY for missing dates
        blank_df = pd.DataFrame({
            "dt": missing_dts,
            "sentiment": 0.0,
            "is_blank": True,
            "asset": events_df["asset"].iat[0],
            "event_dt": missing_dts
        })
        
        combined = pd.concat([events_df, blank_df]).sort_values("dt")
        
        return combined

    @staticmethod
    def apply_decay(filled_events_df: pd.DataFrame, lambda_denom: float) -> pd.DataFrame:
        """Apply exponential decay to sentiment scores."""
        if len(filled_events_df) == 0:
            return pd.DataFrame()
        
        events_df = filled_events_df.copy()    
        
        timestamps = events_df['event_dt'].astype('int64') // 10**9 # convert to seconds
        sentiment_scores = events_df['sentiment'].values
        is_blank = events_df.get('is_blank', pd.Series([False] * len(events_df))).values
        
        time_diffs = np.diff(timestamps, prepend=timestamps[0])
        deltas = np.abs(time_diffs) / lambda_denom
        decay_factors = np.round(np.exp(-deltas) * 1000) / 1000.0
        
        N_weights = np.where(is_blank, 0.001, np.maximum(0.01, np.abs(sentiment_scores)))
        
        decayed_scores = np.zeros(len(events_df))
        total_weights = np.zeros(len(events_df))
        
        for i in range(len(events_df)):
            if i == 0:
                total_weights[i] = N_weights[i]
                decayed_scores[i] = sentiment_scores[i]
            else:
                decay = decay_factors[i]
                total_weights[i] = round(
                    (total_weights[i-1] * decay + N_weights[i]) * 1000
                ) / 1000.0
                
                if total_weights[i] > 0:
                    decayed_scores[i] = round(
                        ((decayed_scores[i-1] * total_weights[i-1] * decay + 
                          N_weights[i] * sentiment_scores[i]) / total_weights[i]) * 1000
                    ) / 1000.0
                else:
                    decayed_scores[i] = decayed_scores[i-1]
        
        events_df['decayed_score'] = decayed_scores
        events_df['total_weight'] = total_weights
        
        return events_df
        
    @staticmethod
    def prepare_features(events_dict: Dict[str, pd.DataFrame],
        prices_dict: Dict[str, pd.DataFrame], 
        asset_names: List[str],
        lambda_denom: float,
        l_threshold: float, 
        s_threshold: float) -> Tuple[np.ndarray, np.ndarray]:

        feature_cols = ['decayed_score', 'open', 'asset_class']
        all_dfs = []
    
        for asset in asset_names:
            events_df = events_dict[asset].copy()
            price_df = prices_dict[asset].copy()

            events_df['dt'] = pd.to_datetime(events_df['dt'])
            price_df['dt'] = pd.to_datetime(price_df['dt'])

            # Apply decay
            decayed_df = FeatureEngineering.apply_decay(events_df, lambda_denom)

            # Merge with price data
            merged_df = decayed_df.merge(
            price_df[['dt', 'return', 'open', 'close']], 
            on='dt', 
            how='inner'
            )

            merged_df = merged_df.sort_values('dt').reset_index(drop=True)

            # Labeling
            def label_return(r):
                if r > l_threshold:
                    return 1  # Long
                elif r < -s_threshold:
                    return 2  # Short
                else:
                    return 0  # Idle
                

            merged_df["signal"] = merged_df["return"].apply(label_return)

            merged_df["asset_class"] = asset_names.index(asset)

            merged_df.dropna(inplace=True)

            if not merged_df.empty:
                all_dfs.append(merged_df)

        if not all_dfs:
            return np.array([]), np.array([])

        # Combine all dataframes and sort by datetime
        combined_df = pd.concat(all_dfs, ignore_index=True).sort_values('dt')
        
        # Extract features and labels
        X = combined_df[feature_cols].values
        y = combined_df['signal'].values

        logger.debug(f"Features: {X.shape[1]} features, {len(y)} samples")
        logger.debug(f"Class distribution: {np.bincount(y)}")

        return X, y
    
        
    @staticmethod
    def weighted_model_score(events_df: pd.DataFrame) -> pd.DataFrame:
        """Calculate weighted sentiment scores while preserving asset column."""
        if events_df.empty:
            return pd.DataFrame(columns=['dt', 'sentiment', 'asset'])

        events_df = events_df.copy()
        evaluator_weights = {"nlp": 1, "gemini": 2, "deepseek": 3}
        
        events_df['evaluator_weight'] = events_df['evaluator'].map(evaluator_weights)
        events_df['weighted_score'] = events_df['sentiment'] * events_df['evaluator_weight']
        
        grouped = events_df.groupby('id', as_index=False).agg({
            'weighted_score': 'sum',
            'evaluator_weight': 'sum',
            'asset': 'first',
            'dt': 'first'
        })
        
        grouped['sentiment'] = grouped['weighted_score'] / grouped['evaluator_weight']
        return grouped[['dt', 'sentiment', 'asset']]