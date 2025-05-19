package com.devbrovki.sentiment.api;

import org.springframework.stereotype.Component;

@Component
public class CryptoPanicStrategy {//implements Strategy{
    /*
    private final ApiService apiService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    long anchor = -1;

    boolean isHistorical = true;

    @Autowired
    public CryptoPanicStrategy(ApiService apiService, KafkaTemplate<String, String> kafkaTemplate) {
        this.apiService = apiService;
        this.kafkaTemplate = kafkaTemplate;
    }

    private String currentUrl;

    @Override
    public void execute(Asset asset, int partition) {

        currentUrl = asset.getUrl() + "&currencies=" + asset.getName();

        Mono<String> responseMono = apiService.request(currentUrl);

        responseMono.subscribe((currentResponse) -> {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode jsonNode = objectMapper.readTree(currentResponse);
                JsonNode resultNode = jsonNode.get("results");

                // send updates to Kafka
                for (int i = resultNode.size() - 1 ; i >= 0; i--) {
                    Result result = new Result();
                    result.setUrl(resultNode.get(i).get("url").asText());
                    result.setTitle(resultNode.get(i).get("title").asText());
                    result.setDescription("");
                    long timestamp = Instant.parse(resultNode.get(i).get("published_at").asText()).toEpochMilli();
                    result.setTimeStamp(timestamp);
                    long id = resultNode.get(i).get("id").asLong();
                    result.setId(id);

                    if (id > anchor) {
                        anchor = id;
                        if (!isHistorical) {
                            sendUpdateToKafka(asset.getName(), objectMapper.writeValueAsString(result));
                        }
                    }

                    if (isHistorical) {
                        sendUpdateToKafka(asset.getName(), objectMapper.writeValueAsString(result));
                    }

                }

                if(!jsonNode.get("previous").isNull()) {
                    currentUrl = jsonNode.get("previous").asText();
                    Asset asset1 = Asset.builder()
                            .url(currentUrl)
                            .pages(asset.getPages())
                            .name(asset.getName())
                            .source(asset.getSource())
                            .build();
                    execute(asset, partition);
                }else{
                    isHistorical = false;
                }

            } catch (JsonProcessingException e) {
                System.out.println("Error processing JSON response: " + e);
            }
        });
    }

    private void sendUpdateToKafka(String coin, String result) {
        kafkaTemplate.send("api-updates", coin, result);
    }

     */
}
