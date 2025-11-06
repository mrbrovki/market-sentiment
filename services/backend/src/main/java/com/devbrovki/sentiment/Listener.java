package com.devbrovki.sentiment;

import com.devbrovki.sentiment.dto.DecayedScore;
import com.devbrovki.sentiment.dto.OptimizationResultDTO;
import com.devbrovki.sentiment.dto.SentimentScore;
import com.devbrovki.sentiment.services.ModelStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Listener {
    private final ModelStore modelStore;

    @Autowired
    public Listener(ModelStore modelStore) {
        this.modelStore = modelStore;
    }

    @KafkaListener(topics = "sentiment-scores", containerFactory = "sentimentListenerContainerFactory")
    public void listenScores(SentimentScore sentimentScore) {
        modelStore.addSentiment(sentimentScore.asset(), sentimentScore);
    }

    @KafkaListener(topics = "model-decayed-scores", containerFactory = "decayedScoreListenerContainerFactory")
    public void listenScores(DecayedScore decayedScore) {
        modelStore.addDecayedScore(decayedScore.asset(), decayedScore);
    }

    @KafkaListener(topics = "model-params", containerFactory = "modelParamsListenerContainerFactory")
    private void listenModelParams(OptimizationResultDTO modelParams) {
        modelStore.setBestOptimizationResultDTO(modelParams);
    }
}
