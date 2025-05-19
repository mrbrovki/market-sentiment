package com.devbrovki.sentiment.api;


public interface Strategy {
    void execute(Asset asset, int partition);
}
