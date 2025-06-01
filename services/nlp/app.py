from kafka import KafkaConsumer, KafkaProducer, TopicPartition
from transformers import pipeline
import json
import time
import os
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

device = 0
pipe = pipeline("text-classification", model="ProsusAI/finbert", device=device)

# Kafka Configuration
KAFKA_BROKER = os.getenv("KAFKA_BROKER", "localhost:9094")
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "events")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "sentiment-nlp")

PARTITIONS      = list(range(0, 8))

def initialize_kafka_producer():
    while True:
        try:
            producer = KafkaProducer(
                bootstrap_servers=[KAFKA_BROKER],
                value_serializer=lambda v: json.dumps(v).encode('utf-8')
            )
            logger.info("Kafka producer connected.")
            return producer
        except Exception as e:
            logger.warning(f"Producer init failed: {e}. Retrying in 5s...")
            time.sleep(5)

# Process messages from Kafka
def process_messages(consumer, producer):
    for message in consumer:
        try:
            # Parse the incoming message
            data = json.loads(message.value.decode('utf-8'))
            logger.info(f"Received message: {data}")

            # Validate and process necessary keys
            title = data.get("title", "").strip()
            content = data.get("content", "").strip()

            if not title:
                logger.error("title empty!")
                continue

            logger.info(f"Processing title: {title}")

            # Run title classification
            result = pipe(title, truncation=True, max_length=512)
            weights = {"positive": 1, "negative": -1, "neutral": 0}
            titleScore = result[0]['score'] * weights[result[0]['label']]
            score = titleScore

            if(content != ""):
                # Run content classification
                result = pipe(content, truncation=True, max_length=512)
                contentScore = result[0]['score'] * weights[result[0]['label']]
                score = (0.8 * score) + (0.2 * contentScore)


            print(f"classification result: {score}")

            data["score"] = score

            sentiment_result = data

            # Send the result to the output Kafka topic
            producer.send(OUTPUT_TOPIC, value=sentiment_result, partition=message.partition)
            print(f"Result sent to topic: {OUTPUT_TOPIC}")

        except json.JSONDecodeError:
            print("Error decoding JSON message, skipping.")
        except KeyError as e:
            print(f"Missing key in message: {e}, skipping.")
        except Exception as e:
            print(f"Error processing message: {e}")


if __name__ == "__main__":
    time.sleep(10)
    producer = initialize_kafka_producer()
    consumer = KafkaConsumer(
        group_id='nlp',
        bootstrap_servers=[KAFKA_BROKER],
        auto_offset_reset='earliest',
    )
    tps = [TopicPartition(INPUT_TOPIC, p) for p in PARTITIONS]
    consumer.assign(tps)

    process_messages(consumer, producer)
