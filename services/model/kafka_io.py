import json
import time
from kafka import KafkaConsumer, TopicPartition
from utils.logger import get_logger
from config import Config

logger = get_logger(__name__)

class KafkaIO:
    """Handle Kafka connections and offsets."""

    @staticmethod
    def initialize_kafka_consumer(config: Config):
        logger.info(f"Connecting to Kafka broker: {config.kafka_broker}")
        while True:
            try:
                consumer = KafkaConsumer(
                    config.input_topic,
                    group_id=config.group_id,
                    bootstrap_servers=[config.kafka_broker],
                    auto_offset_reset="earliest",
                    enable_auto_commit=True,
                    value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                )
                logger.info(f"Connected to topic {config.input_topic}")
                return consumer
            except Exception as e:
                logger.warning(f"Kafka connection failed: {e}. Retrying in 5s...")
                time.sleep(5)

    @staticmethod
    def reset_consumer_offset(consumer: KafkaConsumer, config: Config):
        """Reset consumer to read from beginning."""
        logger.info("Resetting Kafka offset to beginning...")
        partitions = consumer.partitions_for_topic(config.input_topic)
        if not partitions:
            logger.warning("No partitions found for topic.")
            return
        topic_partitions = [TopicPartition(config.input_topic, p) for p in partitions]
        consumer.assign(topic_partitions)
        consumer.seek_to_beginning(*topic_partitions)
        logger.info("Kafka offsets reset successfully.")
