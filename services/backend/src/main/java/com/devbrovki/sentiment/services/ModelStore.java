package com.devbrovki.sentiment.services;

import com.devbrovki.sentiment.dto.DecayedScore;
import com.devbrovki.sentiment.dto.OptimizationResultDTO;
import com.devbrovki.sentiment.dto.SentimentScore;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelStore {
    private final Map<String, List<SentimentScore>> sentimentMap = new ConcurrentHashMap<>();
    private final Map<String, List<DecayedScore>> decayedMap = new ConcurrentHashMap<>();
    @Setter
    @Getter
    private OptimizationResultDTO bestOptimizationResultDTO;

    public void addSentiment(String asset, SentimentScore score) {
        sentimentMap
                .computeIfAbsent(asset, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(score);
    }

    public List<SentimentScore> getSentiments(String asset) {
        List<SentimentScore> scores = sentimentMap.get(asset);
        if (scores == null) return Collections.emptyList();

        synchronized (scores) { // snapshot safely
            return new ArrayList<>(scores);
        }
    }

    public void addDecayedScore(String asset, DecayedScore score) {
        decayedMap
                .computeIfAbsent(asset, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(score);
    }

    public List<DecayedScore> getDecayedScores(String asset) {
        List<DecayedScore> scores = decayedMap.get(asset);
        if (scores == null) return Collections.emptyList();

        synchronized (scores) { // snapshot safely
            return new ArrayList<>(scores);
        }
    }
}
