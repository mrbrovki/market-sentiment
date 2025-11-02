package com.devbrovki.sentiment.dto;


import java.util.UUID;

public record SentimentScore(
        String asset,
        String title,
        String url,
        UUID id,
        long timestamp,
        double score,
        String evaluator
){}
