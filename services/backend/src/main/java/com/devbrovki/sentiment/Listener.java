package com.devbrovki.sentiment;

import com.devbrovki.sentiment.model.Event;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Listener {
    @Value("${num.assets}")
    private int numAssets;

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, List<DeferredResult<List<Event>>>> waiters;

    @PostConstruct
    public void init() {
        BackendApplication.events = new Map[numAssets];


        for (int i = 0; i < BackendApplication.events.length; i++) {
           BackendApplication.events[i] = new ConcurrentHashMap<>();
        }
    }

    @Autowired
    public Listener(SimpMessagingTemplate messagingTemplate, Map<String, List<DeferredResult<List<Event>>>> waiters) {
        this.messagingTemplate = messagingTemplate;
        this.waiters = waiters;
    }

    @KafkaListener(groupId="backend", topics = "chart", containerFactory = "eventListenerContainerFactory")
    public void listenChart(ConsumerRecord<String, Event> record) {
        int partition = record.partition();
        Headers consumedHeaders = record.headers();

        double denom = -1;

        for (Header header : consumedHeaders) {
            if ("lambdaDenominator".equals(header.key())) {
                denom = ByteBuffer.wrap(header.value()).getDouble();
                break;
            }
        }
        if(denom != -1){
            synchronized (BackendApplication.lock) {

                String denomStr = BigDecimal.valueOf(denom).setScale(0, RoundingMode.HALF_UP).toPlainString();

                BackendApplication.events[partition]
                        .computeIfAbsent(denomStr, k -> new ArrayList<>())
                        .add(record.value());


                String key = partition + "_" + denomStr;

                for (DeferredResult<List<Event>> deferredResult : waiters.getOrDefault(key, new ArrayList<>())) {
                    deferredResult.setResult(BackendApplication.events[partition].get(denomStr));
                }
            }
        }
    }







    /*
    private SentimentResult convertToSentimentResult(Map<String, Object> record) {
        SentimentResult sentimentResult = new SentimentResult();
        sentimentResult.setId((Integer) record.get("id"));
        sentimentResult.setTimeStamp((long) record.get("timeStamp"));
        sentimentResult.setScore(((Number) record.get("score")).doubleValue());
        return sentimentResult;
    }

    private void writeResultToCSV(File file, SentimentResult result) {
        FileWriter fileWriter = null;
        PrintWriter printWriter = null;

        try {
            // Open file in append mode
            fileWriter = new FileWriter(file, true);
            printWriter = new PrintWriter(fileWriter);

            // Write CSV headers if the file is empty
            if (file.length() == 0) {
                printWriter.println("id,timestamp,score");  // Example headers
            }

            // Write event details to the CSV
            printWriter.println(result.getId() + "," + result.getTimeStamp() + "," + result.getScore());

            // Flush the writer to ensure data is written to disk
            printWriter.flush();

        } catch (IOException e) {
            e.printStackTrace();  // Handle potential IOExceptions
        } finally {
            if (printWriter != null) {
                printWriter.close();
            }
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        }
     */

}
