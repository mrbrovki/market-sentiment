package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.model.Asset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Listener {
    private final ApplicationContext applicationContext;
    private final List<Strategy> strategies;

    public Listener(ApplicationContext applicationContext,List<Strategy> strategies) {
        this.applicationContext = applicationContext;
        this.strategies = strategies;
    }

    @KafkaListener(topics = "assets", groupId = "scraper", containerFactory = "kafkaListenerContainerFactoryAsset")
    public void listenEvents(ConsumerRecord<String, Asset> consumerRecord) {
        Asset asset = consumerRecord.value();

        InvestingDotComStrategy strategy =
                applicationContext.getBean(InvestingDotComStrategy.class);
        Context investingContext = new Context();

        investingContext.setAsset(asset);
        investingContext.init(strategy);

        System.out.println("Received new assets: " + asset.getName());

        strategies.add(strategy);
    }
}
