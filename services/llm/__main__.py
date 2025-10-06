from config import logger
from rate_limit import RateLimiterThread, RateLimitState
from utils import load_prompts
from kafka_io import initialize_kafka_producer, initialize_kafka_consumer
from worker import process_messages

if __name__ == "__main__":
    rateLimitState = RateLimitState()
    prompts = load_prompts()
    producer = initialize_kafka_producer()
    consumer = initialize_kafka_consumer()

    asset_queues = {}

    # start the rate limiter thread
    RateLimiterThread(rateLimitState).start()

    try:
        process_messages(asset_queues, consumer, producer, prompts, rateLimitState)
    except KeyboardInterrupt:
        logger.info("Shutdown requested, exiting...")
        producer.close()
        consumer.close()
