package com.devbrovki.sentiment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentimentResult {
    private String asset;
    private String evaluator;
    private String source;
    private String id;
    private long timestamp;
    private double score;
}

