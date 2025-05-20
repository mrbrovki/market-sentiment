import os
import json
import logging
import threading
import time
from queue import Queue, Empty
from kafka import KafkaConsumer, KafkaProducer
import requests
import csv

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "localhost:9094")
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "events")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "sentiment-gemini")

HTTP_ENDPOINT = os.getenv("HTTP_ENDPOINT")
API_KEY = os.getenv("GEMINI_API_KEY")

if not API_KEY:
    raise ValueError("API key for Gemini API is not set.")

params = {"key": API_KEY}

NUM_PARTITIONS = 8
BATCH_SIZE = 5
FLUSH_INTERVAL = 30
API_CD = 30
MAX_RETRIES = 2
BATCH_MAX_SIZE = 10

prompts = {}


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

class PartitionWorker(threading.Thread):
    def __init__(self, partition_id, queue):
        super().__init__(daemon=True)
        self.partition_id = partition_id
        self.queue = queue
        self.buffer = []
        self.last_flush = time.time()
        self.attempts = 0
        self.last_send_time = 0 

    def run(self):
        while True:
            now = time.time()
            # Pull as many as we can without blocking
            try:
                while True:
                    msg = self.queue.get(block=False)
                    self.buffer.append(msg)
            except Empty:
                pass

            should_flush = (
                self.buffer
                and (len(self.buffer) >= BATCH_SIZE or (now - self.last_flush) >= FLUSH_INTERVAL)
                and (now - self.last_send_time >= API_CD))

            if should_flush:
        
                num_to_send = min(BATCH_MAX_SIZE, len(self.buffer))
                
                success = send_to_api(self.buffer[:num_to_send].copy(), self.partition_id)
                self.attempts += 1
                self.last_send_time = time.time()

                if success or self.attempts > MAX_RETRIES:
                    if not success:
                        logger.error(f"[P{self.partition_id}] Dropping {len(self.buffer)} msgs after {self.attempts} tries")
                    self.buffer = self.buffer[num_to_send:]
                    self.attempts = 0
                    self.last_flush = now
            time.sleep(0.8)

def send_to_api(messages, partition_id):
    fields_to_send = ["id", "content", "title"]
    fields_to_receive = ["id", "score"]

    messages_to_send = []
    id_map          = {}

    for new_id, msg in enumerate(messages):
        # filter down to only the fields to send
        filtered = {field: msg[field] for field in fields_to_send if field in msg}

        # remember how to map new_id → original id
        original_id = filtered.get("id")
        if original_id is not None:
            id_map[new_id] = original_id

        # override the id to be sequential (as the API expects)
        filtered["id"] = new_id

        messages_to_send.append(filtered)

    # build the payload
    payload = {
        "contents": [{
            "parts": [{
                "text": prompts[partition_id]
                        + "Here is JSON Input: "
                        + json.dumps(messages_to_send)
            }]
        }]
    }

    try:
        resp = requests.post(HTTP_ENDPOINT,
                             json=payload,
                             params=params,
                             timeout=60)
        resp.raise_for_status()
        results = extract_scores(resp.json())

        # results come back keyed by your new_id; map them back onto the original messages
        for r in results:
            new_id = r["id"]
            orig_id = id_map.get(new_id)
            if orig_id is None:
                continue   # (or raise an error)

            # find the original message object
            # (assumes `messages` is a list of dicts and 'id' is unique)
            orig_msg = next(msg for msg in messages if msg.get("id") == orig_id)

            # copy over whatever fields the API returned
            for field in fields_to_receive[1:]:
                if field in r:
                    orig_msg[field] = r[field]
                    

        # if we got back exactly one result per message, produce them all
        if len(results) == len(messages):
            for event in messages:
                producer.send(OUTPUT_TOPIC,
                              value=event,
                              partition=partition_id)
                logger.info(f'Sent {len(messages)} messages!')
            return True
        else:
            logger.info("length mismatch " + str(len(results)) + " " +str(len(messages)))
            return False
    except Exception as e:
        logger.error(f"[P{partition_id}] API error: {e}")
    return False

def extract_scores(api_json):
    results = []
    candidates = api_json.get("candidates", [])
    for candidate in candidates:
            content = candidate.get("content", {})
            parts = content.get("parts", [])

            for part in parts:
                text = part.get("text", "")
                
                firstIndex = text.find("[")
                lastIndex = text.rfind("]")
                try:
                    results = json.loads(text[firstIndex:lastIndex+1])
                except json.JSONDecodeError as e:
                    logger.error(f"Error decoding JSON: {e}")
                    return [] 
    return results


def consume_messages():
    consumer = KafkaConsumer(
        INPUT_TOPIC,
        group_id='gemini',
        bootstrap_servers=[KAFKA_BROKER],
        auto_offset_reset='earliest',
        request_timeout_ms=30000,
        session_timeout_ms=10000,
        heartbeat_interval_ms=3000,
    )

    logger.info("Kafka consumer connected.")

    for message in consumer:
        try:
            payload = json.loads(message.value)
            payload.pop("url", None)

            # trim content
            if len(payload.get("content","")) > 350:
                payload["content"] = payload["content"][:350] + "..."
            part = message.partition
            if part in partition_queues:
                partition_queues[part].put(payload)
            else:
                logger.warning(f"Received message for unexpected partition {part}")
        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON: {e}")

        

if __name__ == "__main__":
    with open('assets.csv', newline='') as csvf:
        reader = csv.reader(csvf)
        for partition_str, asset in reader:
            pid = int(partition_str)
            with open(f"prompts/{asset}.txt", 'r') as tf:
                prompts[pid] = tf.read()


    producer = initialize_kafka_producer()
    # create and start one worker per partition
    partition_queues = {pid: Queue() for pid in range(NUM_PARTITIONS)}

    for pid, q in partition_queues.items():
        PartitionWorker(pid, q).start()

    try:
        consume_messages()
    except KeyboardInterrupt:
        logger.info("Shutdown requested, exiting...")
        producer.close()
