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
    private String url;
    private String title;
    private String content;
    private String id;
    private long timeStamp;
    private double score;
}

