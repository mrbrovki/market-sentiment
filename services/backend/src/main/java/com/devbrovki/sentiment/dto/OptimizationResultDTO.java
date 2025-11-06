package com.devbrovki.sentiment.dto;

public record OptimizationResultDTO(
        boolean success,
        double testScore,
        BestParams bestParams,
        double optunaBestValue,
        String timestamp
) {
    public record BestParams(
            double lambdaDenom,
            double longThreshold,
            double shortThreshold
    ) {}
}
