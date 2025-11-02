package com.devbrovki.sentiment;

import com.devbrovki.sentiment.dto.DecayedScore;
import com.devbrovki.sentiment.dto.SentimentScore;
import com.devbrovki.sentiment.services.DecayedStore;
import com.devbrovki.sentiment.services.SentimentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Listener {
    private final SentimentStore sentimentStore;
    private final DecayedStore decayedStore;

    @Autowired
    public Listener(SentimentStore sentimentStore, DecayedStore decayedStore) {
        this.sentimentStore = sentimentStore;
        this.decayedStore = decayedStore;
    }

    @KafkaListener(topics = "sentiment-scores", containerFactory = "sentimentListenerContainerFactory")
    public void listenScores(SentimentScore sentimentScore) {
        sentimentStore.addSentiment(sentimentScore.asset(), sentimentScore);
    }

    @KafkaListener(topics = "model-decayed-scores", containerFactory = "decayedScoreListenerContainerFactory")
    public void listenScores(DecayedScore decayedScore) {
        decayedStore.addSentiment(decayedScore.asset(), decayedScore);
    }
}
