package com.devbrovki.sentiment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OptimizationResultDTO(
        boolean success,
        @JsonProperty("test_score") double testScore,
        @JsonProperty("best_params") BestParams bestParams,
        @JsonProperty("optuna_best_value") double optunaBestValue,
        String timestamp
) {
    public record BestParams(
            double lambdaDenom,
            @JsonProperty("l_threshold") double lThreshold,
            @JsonProperty("s_threshold") double sThreshold
    ) {}
}
