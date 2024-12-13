package com.example.demo.api;

import com.example.demo.model.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
public class CryptoPanicStrategy implements Strategy{
    private final ApiService apiService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    public CryptoPanicStrategy(ApiService apiService, KafkaTemplate<String, String> kafkaTemplate) {
        this.apiService = apiService;
        this.kafkaTemplate = kafkaTemplate;
    }

    private String currentUrl;
    private long lastTimestamp = -1;

    @Override
    public void execute(String url, String coin) {
        currentUrl = url + "&currencies=" + coin;

        Mono<String> responseMono = apiService.request(currentUrl);

        responseMono.subscribe((currentResponse) -> {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode jsonNode = objectMapper.readTree(currentResponse);


                JsonNode resultNode = jsonNode.get("results");

                // send updates to Kafka
                for (int i = 0; i < resultNode.size(); i++) {
                    Result result = new Result();
                    result.setUrl(resultNode.get(i).get("url").asText());
                    result.setTitle(resultNode.get(i).get("title").asText());
                    long timestamp = Instant.parse(resultNode.get(i).get("published_at").asText()).toEpochMilli();
                    result.setTimeStamp(timestamp);

                    if(timestamp > lastTimestamp) {
                        lastTimestamp = timestamp;
                        sendUpdateToKafka(coin, objectMapper.writeValueAsString(result));
                    }
                }

                if(!jsonNode.get("previous").isNull()) {
                    currentUrl = jsonNode.get("previous").asText();
                    execute(currentUrl, coin);
                }
            } catch (JsonProcessingException e) {
                System.out.println("Error processing JSON response: " + e);
            }
        });
    }

    private void sendUpdateToKafka(String coin, String result) {
        kafkaTemplate.send("api-updates", coin, result);
    }
}
