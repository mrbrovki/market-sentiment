package com.devbrovki.sentiment;

import com.devbrovki.sentiment.model.Event;
import com.devbrovki.sentiment.model.SentimentResult;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;


import java.io.*;
import java.util.Arrays;

@SpringBootApplication
@EnableKafka
public class LoggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoggerApplication.class, args);
    }

    @Component

    public static class StartupRunner implements CommandLineRunner {

        @Autowired
        private KafkaTemplate<String, Event> kafkaTemplateEvent;

        @Autowired
        private KafkaTemplate<String, SentimentResult> kafkaTemplateSentiment;

        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        @Autowired
        private KafkaListenerEndpointRegistry registry;

        @Override
        public void run(String... args) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectReader eventReader = mapper.readerFor(Event.class);
            ObjectReader sentimentReader = mapper.readerFor(SentimentResult.class);

            String outputDirPath = "output/";
            File outputDir = new File(outputDirPath);
            if (!outputDir.exists() || !outputDir.isDirectory()) return;
            File[] dirs = outputDir.listFiles((dir, name) -> new File(dir, name).isDirectory());
            if (dirs == null || dirs.length == 0) return;
            Arrays.sort(dirs, java.util.Comparator.comparing(File::getName));

            for (File assetDirFile : dirs) {
                //  EVENTS
                File eventsDirFile = new File(assetDirFile, "events");
                File[] eventSourceFiles = eventsDirFile.listFiles((dir, name) -> name.endsWith(".json"));
                if (eventSourceFiles != null && eventSourceFiles.length > 0){
                    // deterministic order
                    java.util.Arrays.sort(eventSourceFiles, java.util.Comparator.comparing(File::getName));

                    for (File eventSourceFile : eventSourceFiles) {
                        try (MappingIterator<Event> it = eventReader.readValues(eventSourceFile)) {
                            while (it.hasNext()) {
                                Event event = it.next();
                                // process each Event without loading the whole list
                                kafkaTemplateEvent.send("events", event.getAsset(), event);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                kafkaTemplateEvent.flush();

                //  SENTIMENT-SCORES
                File sentimentDirFile = new File(assetDirFile, "sentiment");
                File[] evaluatorFiles = sentimentDirFile.listFiles((dir, name) -> name.endsWith(".json"));
                if (evaluatorFiles != null && evaluatorFiles.length > 0){
                    // deterministic order
                    java.util.Arrays.sort(evaluatorFiles, java.util.Comparator.comparing(File::getName));

                    for (File evaluatorFile : evaluatorFiles) {
                        try (MappingIterator<SentimentResult> it = sentimentReader.readValues(evaluatorFile)) {
                            while (it.hasNext()) {
                                SentimentResult sentimentResult = it.next();
                                // process each sentimentResult without loading the whole list
                                kafkaTemplateSentiment.send("sentiment-scores", sentimentResult.getAsset(), sentimentResult);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            kafkaTemplateSentiment.flush();

            registry.start();
        }
    }
}