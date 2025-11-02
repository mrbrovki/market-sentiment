package com.devbrovki.sentiment.dto;

import java.util.List;

public record PriceData(
        String ticker,
        String interval,
        long startTS,
        long endTS,
        List<PriceCandle> results
) {}

