import json
import time
from kafka import KafkaConsumer, KafkaProducer, TopicPartition
from utils.logger import get_logger
from config import Config

logger = get_logger(__name__)

class KafkaIO:
    """Handle Kafka connections and offsets."""

    @staticmethod
    def initialize_kafka_consumer(config: Config):
        logger.info(f"Connecting to Kafka broker: {config.KAFKA_BROKER}")
        while True:
            try:
                consumer = KafkaConsumer(
                    config.INPUT_TOPIC,
                    group_id=config.GROUP_ID,
                    bootstrap_servers=[config.KAFKA_BROKER],
                    auto_offset_reset="earliest",
                    enable_auto_commit=True,
                    value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                )
                logger.info(f"Connected to topic {config.INPUT_TOPIC}")
                return consumer
            except Exception as e:
                logger.warning(f"Kafka connection failed: {e}. Retrying in 5s...")
                time.sleep(5)

    @staticmethod
    def initialize_kafka_producer(config: Config):
        while True:
            try:
                producer = KafkaProducer(
                    bootstrap_servers=[config.KAFKA_BROKER],
                    value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                    key_serializer=lambda k: k.encode('utf-8') if k else None
                )
                logger.info("Kafka producer connected.")
                return producer
            except Exception as e:
                logger.warning(f"Producer init failed: {e}. Retrying in 5s...")
                time.sleep(5)