import json
from config import logger, pipe, weights, GROUP_ID
from kafka_io import sendEvent

def process_messages(consumer, producer):
    for message in consumer:
        try:
            # Parse the incoming message
            event = json.loads(message.value)
            event.pop("url", None)

            logger.info(f"Received a message!")

            # Validate and process necessary keys
            title = event.get("title")
            content = event.get("content")

            score = None

            # Run title classification
            result = pipe(title, truncation=True, max_length=512)
            titleScore = result[0]['score'] * weights[result[0]['label']]

            if(content != ""):
                # Run content classification
                result = pipe(content, truncation=True, max_length=2048)
                contentScore = result[0]['score'] * weights[result[0]['label']]

                score = (0.7 * titleScore) + (0.3 * contentScore)
            else:
                score = titleScore

            print(f"Classification result: {score}")

            event["score"] = score
            event["evaluator"] = GROUP_ID

            sendEvent(producer, event, event.get("asset"))

            logger.info(f'Message sent!')

        except json.JSONDecodeError:
            print("Error decoding JSON message, skipping.")
        except Exception as e:
            print(f"Error processing message: {e}")