import threading
import time
import json
import requests
from queue import Empty, Queue
from config import MAX_RETRIES, MIN_BATCH_SIZE, MAX_BATCH_SIZE, FLUSH_INTERVAL, HTTP_ENDPOINT, logger, LLM_LOCAL, GROUP_ID, MODEL, API_KEY, WOL_ENDPOINT, session
from utils import extract_scores_remote_LLM, extract_scores_local_LLM, wake_on_lan_request
from kafka_io import sendEvent

class AssetWorker(threading.Thread):
    def __init__(self, asset_id, queue, producer, prompts, rateLimitState):
        super().__init__(daemon=True)
        self.asset_id = asset_id
        self.queue = queue
        self.buffer = []
        self.last_flush = time.time()
        self.attempts = 0
        self.producer = producer
        self.prompts = prompts
        self.rateLimitState = rateLimitState

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
                and (len(self.buffer) >= MIN_BATCH_SIZE 
                     or (now - self.last_flush) >= FLUSH_INTERVAL)
                and self.rateLimitState.can_consume_batch())

            if should_flush:
                num_to_send = min(MAX_BATCH_SIZE, len(self.buffer))
                num_to_send //= (self.attempts + 1)

                if num_to_send == 0:
                    num_to_send = 1

                success = send_to_api(self.buffer[:num_to_send].copy(), self.asset_id, self.producer, self.prompts)
                
                if not success and self.attempts < MAX_RETRIES: 
                    self.attempts += 1
                    logger.info(f"[A{self.asset_id}] Retry {num_to_send} msgs: {self.attempts}")
                    time.sleep(2 ** self.attempts)  # Exponential backoff

                if not success and self.attempts == MAX_RETRIES:
                    dropped = self.buffer[:num_to_send]
                    logger.error(f"[A{self.asset_id}] Dropping {len(dropped)} msgs after {self.attempts} retries. IDs: {[m.get('id') for m in dropped]}")
                
                if success or self.attempts == MAX_RETRIES:
                    self.buffer = self.buffer[num_to_send:]
                    self.attempts = 0
                    self.last_flush = now
            time.sleep(1)


def send_to_api(messages, asset_id, producer, prompts):
    fields_to_send = ["id", "content", "title"]
    fields_to_receive = ["id", "score"]

    messages_to_send = []
    msg_map          = {}

    for new_id, msg in enumerate(messages):
        # filter down to only the fields to send
        filtered = {field: msg[field] for field in fields_to_send if field in msg}

        # remember how to map new_id → original message
        original_id = filtered.get("id")
        if original_id is not None:
            msg_map[new_id] = msg

        # override the id to be sequential (as the API expects)
        filtered["id"] = new_id

        messages_to_send.append(filtered)

    payload = {}
    params = {}

    if(GROUP_ID == "gemini"):
        payload = {
            "contents": [{
                "role": "user",
                "parts": [{
                    "text": prompts[asset_id]
                            + "Here is JSON Input: "
                            + json.dumps(messages_to_send)
                }]
            }],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "id": { 
                          "type": "INTEGER", "nullable": False, "minimum": 0
                        },
                      "score": {
                        "type": "INTEGER", "nullable": False, "minimum": -1, "maximum": 1
                      }
                    },
                    "propertyOrdering": ["id", "score"]
                    }
                }
            }
        } 
        params = {"key": API_KEY}
    else:
        payload = {
            "model": MODEL,
            "messages": [
                { "role": "system", "content": prompts[asset_id] },
                { "role": "user", "content": json.dumps(messages_to_send) }
            ],
            "temperature": 0.7,
            "max_tokens": -1,
            "stream": bool(False),
            "think": bool(True),
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                  "name": "score_response",
                  "strict": bool(True),
                  "schema": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": {
                            "type": "integer",
                            "minimum": 0
                          },
                          "score": {
                            "type": "number",
                            "minimum": -1,
                            "maximum": 1
                          }
                        },
                        "required": ["id", "score"],
                        "additionalProperties": bool(False)
                      },
                      "minItems": 1
                    }
                }
            },
        }

    try:
        if WOL_ENDPOINT is not None:
            wake_on_lan_request()
            
        resp = session.post(HTTP_ENDPOINT,
                             json=payload,
                             params=params,
                             timeout=(30, 600))

        resp.raise_for_status()

        results = []

        if LLM_LOCAL:
            results = extract_scores_local_LLM(resp.json())
        else:
            results = extract_scores_remote_LLM(resp.json())

        isCorrect = True

        if len(results) != len(messages):
            logger.error(f"[A{asset_id}] Expected {len(messages)} results, got {len(results)}")
            return False

        for r in results:
            new_id = r["id"]
            orig_msg = msg_map.get(new_id, None)
            if orig_msg is None:
                logger.warning(f"Could not map back id {new_id}")
                isCorrect = False
                break

            for field in fields_to_receive[1:]:
                if field in r:
                    orig_msg[field] = r[field]
                else:
                    logger.error(f"[`A{asset_id}] Missing required field '{field}' in result {r}")
                    isCorrect = False
                    break
                    
        if isCorrect:
            for event in messages:
                event = {k: v for k, v in event.items() if k != "content"}
                event["evaluator"] = GROUP_ID

                sendEvent(producer, event, asset_id)

            logger.info(f'Sent {len(messages)} messages!')
            return True
        else:
            logger.error(f"[A{asset_id}] API returned incorrect data format.")
            return False
        
    # In send_to_api exception block:
    except requests.HTTPError as e:
        if e.response is not None and e.response.status_code == 429:
            retry_after = int(e.response.headers.get("Retry-After", 600))
            logger.error(f"[A{asset_id}] 429 Too Many Requests. Sleeping {retry_after}s")
            time.sleep(retry_after)
        else:
            logger.error(f"[A{asset_id}] HTTP error: {e}")
        return False
    except Exception as e:
        logger.error(f"[A{asset_id}] Unexpected API error: {e}")
        return False
    

def process_messages(asset_queues, consumer, producer, prompts, rateLimitState):
    for message in consumer:
        try:
            payload = json.loads(message.value)

            # trim content
            if len(payload.get("content","")) > 2048:
                payload["content"] = payload["content"][:2048] + "..."
            asset_id = payload.get("asset")

            if asset_id not in asset_queues:
                # new asset_id, create a new queue and worker
                logger.warning(f"Received message for unexpected asset_id {asset_id}")

                queue = Queue(2048)
                asset_queues[asset_id] = queue
                AssetWorker(asset_id, queue, producer, prompts, rateLimitState).start()

                logger.info(f"Known asset_ids now: {list(asset_queues.keys())}")

            asset_queues[asset_id].put(payload)

        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON: {e}")