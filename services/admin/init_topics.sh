#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_HOST="kafka"
BOOTSTRAP_PORT="9092"
KT="/opt/kafka/bin/kafka-topics.sh"

echo "⏳ Waiting for Kafka at ${BOOTSTRAP_HOST}:${BOOTSTRAP_PORT}..."
until bash -c "echo > /dev/tcp/${BOOTSTRAP_HOST}/${BOOTSTRAP_PORT}" 2>/dev/null; do
  echo "Kafka not ready, retrying in 5s..."
  sleep 5
done
echo "✅ Kafka is accepting connections!"

create_topic() {
  local topic="$1" retention="$2" segment="$3"
  "$KT" --bootstrap-server "${BOOTSTRAP_HOST}:${BOOTSTRAP_PORT}" --create --if-not-exists \
        --topic "${topic}" --partitions 8 --replication-factor 1 \
        --config "retention.bytes=${retention}" --config "segment.bytes=${segment}"
}

echo "🚀 Creating topics..."
create_topic sentiment-scores   3221225472 536870912
create_topic events             6442450944 536870912
create_topic chart              1073741824 536870912
create_topic map                1073741824 536870912
echo "🎉 Topics created (or already existed)!"
