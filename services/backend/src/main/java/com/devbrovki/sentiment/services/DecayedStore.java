package com.devbrovki.sentiment.services;

import com.devbrovki.sentiment.dto.DecayedScore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DecayedStore {
    private final Map<String, List<DecayedScore>> decayedMap = new ConcurrentHashMap<>();

    public void addSentiment(String asset, DecayedScore score) {
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

    public Map<String, List<DecayedScore>> getAll() {
        Map<String, List<DecayedScore>> snapshot = new HashMap<>();
        decayedMap.forEach((key, list) -> {
            synchronized (list) {
                snapshot.put(key, new ArrayList<>(list));
            }
        });
        return snapshot;
    }

    public void delete(String asset) {
        decayedMap.remove(asset);
    }

    public void clearAll() {
        decayedMap.clear();
    }
}