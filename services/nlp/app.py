from kafka import KafkaConsumer, KafkaProducer
from transformers import pipeline
import json

# Initialize FinBERT pipeline
pipe = pipeline("text-classification", model="ProsusAI/finbert")

# Kafka Configuration
KAFKA_BROKER = 'kafka:9092'
INPUT_TOPIC = 'api-updates'
OUTPUT_TOPIC = 'sentiment'

consumer = KafkaConsumer(INPUT_TOPIC,
                         group_id='my-group',
                         bootstrap_servers=[KAFKA_BROKER], auto_offset_reset='earliest')

producer = KafkaProducer(bootstrap_servers=[KAFKA_BROKER], value_serializer=lambda v: json.dumps(v).encode('utf-8'))

# Process messages from Kafka
def process_messages():
    for message in consumer:
        try:
            prompt = json.loads(message.value.decode('utf-8'))["title"]
            
            if prompt:
                print(f"Processing message: {prompt}")
                
                # Run text classification
                result = pipe(prompt, max_length=8000)
                print(f"Classification result: {result}")
                
                # Send the result to the output Kafka topic
                producer.send(OUTPUT_TOPIC, {'result': result})
                print(f"Result sent to topic: {OUTPUT_TOPIC}")
            else:
                print("Invalid message received, skipping.")
        except Exception as e:
            print(f"Error processing message: {e}")


if __name__ == "__main__":
    process_messages()
