#!/bin/bash

# Ensure the script exits on errors
set -e

# Kafka bootstrap server
BOOTSTRAP_SERVER="kafka:9092"

# Wait for Kafka to be ready
echo "Waiting for Kafka to be ready..."
while ! echo exit | nc -z kafka 9092; do
  echo "Kafka is not ready. Retrying in 5 seconds..."
  sleep 5
done
echo "Kafka is ready!"

# Create topics
echo "Creating topics..."
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic sentiment-gemini --partitions 8 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic sentiment-gpt --partitions 8 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic sentiment-nlp --partitions 8 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic events --partitions 8 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic chart --partitions 1 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic map --partitions 1 --replication-factor 1

echo "Topics created successfully!"