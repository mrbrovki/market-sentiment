import os
import logging
from transformers import pipeline

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "localhost:9094")
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "events")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "sentiment-scores")
GROUP_ID = os.getenv("GROUP_ID")

if not GROUP_ID:
    raise ValueError("GROUP_ID is not set.")


weights = {"positive": 1, "negative": -1, "neutral": 0}
pipe = pipeline("text-classification", model="ProsusAI/finbert", device=0)