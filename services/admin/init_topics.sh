#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_HOST="kafka"
BOOTSTRAP_PORT="9092"
KT="/opt/kafka/bin/kafka-topics.sh"
KP="/opt/kafka/bin/kafka-console-producer.sh"

echo "⏳ Waiting for Kafka at ${BOOTSTRAP_HOST}:${BOOTSTRAP_PORT}..."
until bash -c "echo > /dev/tcp/${BOOTSTRAP_HOST}/${BOOTSTRAP_PORT}" 2>/dev/null; do
  echo "Kafka not ready, retrying in 5s..."
  sleep 5
done
echo "✅ Kafka is accepting connections!"

create_topic() {
  local topic="$1" partitions="$2" retention="$3" segment="$4"
  "$KT" --bootstrap-server "${BOOTSTRAP_HOST}:${BOOTSTRAP_PORT}" --create --if-not-exists \
        --topic "${topic}" --partitions "${partitions}" --replication-factor 1 \
        --config "retention.bytes=${retention}" --config "segment.bytes=${segment}"
}

echo "🚀 Creating topics..."
create_topic sentiment-scores           8 3221225472 536870912
create_topic events                     8 6442450944 536870912
create_topic chart                      8 1073741824 536870912
create_topic assets                     1 1073741824 536870912
create_topic map                        8 1073741824 536870912
create_topic model-params               1 1073741824 536870912
create_topic model-predictions          1 1073741824 536870912
create_topic model-decayed-scores       1 1073741824 536870912
echo "🎉 Topics created (or already existed)!"

# Send a message to the "assets" topic
send_message() {
  local topic="$1"
  local message="$2"

  echo "$message" | "$KP" --bootstrap-server "${BOOTSTRAP_HOST}:${BOOTSTRAP_PORT}" --topic "$topic"
}


# Example usage
# Send messages to the "assets" topic based on your CSV
send_message assets '{"name":"TSLA","url":"https://www.investing.com/equities/tesla-motors-news/{page}","pages":1000,"source":"https://www.investing.com"}'


echo "✅ Sent initial messages to 'assets' topic!"