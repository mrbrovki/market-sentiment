package com.devbrovki.sentiment.api;


import java.util.List;

public interface Strategy {
    void execute();
    void cleanup();
    boolean isFinished();

    void init(Asset asset, List<String> userAgents);
}
