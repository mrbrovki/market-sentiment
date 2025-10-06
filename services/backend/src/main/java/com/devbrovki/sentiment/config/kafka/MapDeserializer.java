package com.devbrovki.sentiment.config.kafka;

import org.apache.kafka.common.serialization.Deserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class MapDeserializer implements Deserializer<Map<Long, double[]>> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<Long, double[]> deserialize(String topic, byte[] data) {
        try {
            return objectMapper.readValue(data, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing Map<Long, double[]>", e);
        }
    }
}
