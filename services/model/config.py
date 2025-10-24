from dataclasses import dataclass
import os

@dataclass
class Config:
    """Configuration for model training service."""
    kafka_broker: str = os.getenv("KAFKA_BROKER", "localhost:9094")
    input_topic: str = os.getenv("INPUT_TOPIC", "sentiment-scores")
    group_id: str = os.getenv("GROUP_ID", "model")

    idle_timeout: int = 7200
    retrain_interval: int = 14400
    output_dir: str = "models"

    n_estimators: int = 200
    random_state: int = 42

    optuna_trials: int = 100