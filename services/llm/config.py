import os
import logging
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "localhost:9094")
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "events")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "sentiment-scores")
GROUP_ID = os.getenv("GROUP_ID")

HTTP_ENDPOINT = os.getenv("HTTP_ENDPOINT")
API_KEY = os.getenv("API_KEY", "")

if not HTTP_ENDPOINT:
    raise ValueError("HTTP endpoint is not set.")

if not GROUP_ID:
    raise ValueError("GROUP_ID is not set.")


MODEL=os.getenv("MODEL", "")
LLM_LOCAL = HTTP_ENDPOINT.startswith("http://")
MIN_BATCH_SIZE = int(os.getenv("MIN_BATCH_SIZE", 4))
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", 8))
FLUSH_INTERVAL = int(os.getenv("FLUSH_INTERVAL", 20))
MAX_RETRIES = int(os.getenv("MAX_RETRIES", 3))
MAX_RPM = int(os.getenv("MAX_RPM", 30))
MAX_RPD = int(os.getenv("MAX_RPD", 14400))
WOL_ENDPOINT = os.getenv("WOL_ENDPOINT", None)

session = requests.Session()
retries = Retry(total=10, backoff_factor=2, status_forcelist=[429,500,502,503,504], connect=10, allowed_methods=["POST"], respect_retry_after_header=True)
adapter = HTTPAdapter(max_retries=retries)
session.mount("https://", adapter)
session.mount("http://", adapter)