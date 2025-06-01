package com.devbrovki.sentiment.api;

import lombok.Setter;

@Setter
public class Context {
    private Asset asset;
    private int partition;

    public void executeStrategy(Strategy strategy) {
        strategy.execute(asset, partition);
    }

    public void cleanup(Strategy strategy) {
        strategy.cleanup();
    }
}
