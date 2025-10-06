from config import logger
from kafka_io import initialize_kafka_producer, initialize_kafka_consumer
from worker import process_messages


if __name__ == "__main__":
    producer = initialize_kafka_producer()
    consumer = initialize_kafka_consumer()
    
    try:
        process_messages(consumer, producer)
    except KeyboardInterrupt:
        logger.info("Shutdown requested, exiting...")
        producer.close()
        consumer.close()
