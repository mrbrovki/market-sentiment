package com.devbrovki.sentiment.services;

import com.devbrovki.sentiment.dto.SentimentScore;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

@Service
public class SentimentStore {
    private final Map<String, List<SentimentScore>> sentimentMap = new ConcurrentHashMap<>();

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

    public Map<String, List<SentimentScore>> getAll() {
        Map<String, List<SentimentScore>> snapshot = new HashMap<>();
        sentimentMap.forEach((key, list) -> {
            synchronized (list) {
                snapshot.put(key, new ArrayList<>(list));
            }
        });
        return snapshot;
    }

    public void delete(String asset) {
        sentimentMap.remove(asset);
    }

    public void clearAll() {
        sentimentMap.clear();
    }
}