package com.devbrovki.sentiment.dto;

public record PriceCandle(
        double open,
        double close,
        double low,
        double high,
        long timestamp
){}