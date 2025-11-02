package com.devbrovki.sentiment.dto;

public record DecayedScore(
        String asset,
        long timestamp,
        long lastTrainTime,
        double decayedScore
) {
}
