from dataclasses import dataclass
import os

@dataclass
class Config:
    """Configuration for model training service."""
    KAFKA_BROKER: str = os.getenv("KAFKA_BROKER", "localhost:9094")
    INPUT_TOPIC: str = os.getenv("INPUT_TOPIC", "sentiment-scores")
    OUTPUT_TOPIC: str = os.getenv("OUTPUT_TOPIC", "model-params")
    GROUP_ID: str = os.getenv("GROUP_ID", "model")

    IDLE_TEMOUT: int = 7200
    RETRAIN_INTERVAL: int = 14400
    OUTPUT_DIR: str = "models"

    n_estimators: int = 200
    random_state: int = 42

    optuna_trials: int = 100