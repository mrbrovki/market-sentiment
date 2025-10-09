#!/bin/bash
set -euo pipefail

echo "🚀 Starting Kafka..."
docker compose up -d \
  kafka \
  kafka-ui

docker compose run --rm kafka-admin
echo "✅ Kafka-admin finished!"

echo "🚀 Starting logging..."
docker compose up -d logger

# browser nodes
docker compose up -d  selenium-hub firefox

# Wait for nodes to register
echo "⏳ Waiting for all Firefox nodes to register..."
sleep 60

echo "🚀 Starting services..."
docker compose up -d \
  scraper \
  sentiment-deepseek \
  sentiment-gemini \
  sentiment-nlp


echo "🎉 All services started: kafka → kafka-admin → others"
