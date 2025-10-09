from config import INPUT_TOPIC, KAFKA_BROKER, OUTPUT_TOPIC, logger, GROUP_ID
from kafka import KafkaConsumer, KafkaProducer
import time
import json

def initialize_kafka_producer():
    while True:
        try:
            producer = KafkaProducer(
                bootstrap_servers=[KAFKA_BROKER],
                value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                key_serializer=lambda k: k.encode('utf-8') if k else None
            )
            logger.info("Kafka producer connected.")
            return producer
        except Exception as e:
            logger.warning(f"Producer init failed: {e}. Retrying in 5s...")
            time.sleep(5)

def initialize_kafka_consumer():
    consumer = KafkaConsumer(
        INPUT_TOPIC,
        group_id=GROUP_ID,
        bootstrap_servers=[KAFKA_BROKER],
        auto_offset_reset='latest',
        request_timeout_ms=30000,
        session_timeout_ms=10000,
        heartbeat_interval_ms=3000,
    )

    logger.info("Kafka consumer connected.")
    return consumer


def sendEvent(producer, event, asset_id):
    producer.send(OUTPUT_TOPIC, value=event, key=asset_id)