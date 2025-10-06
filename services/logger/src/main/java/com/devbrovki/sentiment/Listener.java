package com.devbrovki.sentiment;

import com.devbrovki.sentiment.model.Event;
import com.devbrovki.sentiment.model.SentimentResult;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Component
public class Listener {

    @KafkaListener(topics = "events", groupId = "logger-events", containerFactory = "kafkaListenerContainerFactoryEvent")
    public void listenEvents(ConsumerRecord<String, Event> consumerRecord) throws IOException {
        Event event = consumerRecord.value();

        writeObjectToJsonFile(event, "output" + "/" + event.getAsset() + "/events/" + event.getSource() + ".json");
    }


    @KafkaListener(topics = "sentiment-scores", groupId = "logger-sentiment-scores", containerFactory = "kafkaListenerContainerFactorySentiment")
    public void listenSentiment(ConsumerRecord<String, SentimentResult> consumerRecord) throws IOException {
        SentimentResult sentimentResult = consumerRecord.value();

        writeObjectToJsonFile(consumerRecord.value(), "output" + "/" + sentimentResult.getAsset() + "/sentiment/" + sentimentResult.getEvaluator() + ".json");
    }

    public static <T> void writeObjectToJsonFile(T object, String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().enable(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());

        String json = mapper.writeValueAsString(object);

        File file = new File(path);
        if (!file.exists()) {
            Path filePath = Paths.get(path);
            Files.createDirectories(filePath.getParent());

            try (Writer writer = Files.newBufferedWriter(filePath)) {
                writer.write("[" + json + "]");
            }
        } else {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            long length = raf.length();
            if (length > 1) {
                raf.seek(length - 1);
                raf.writeBytes(",\n" + json + "]");
            } else {
                raf.writeBytes("[" + json + "]");
            }
            raf.close();
        }
    }
}
