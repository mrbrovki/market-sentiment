package com.devbrovki.sentiment.api;


import com.devbrovki.sentiment.model.Asset;
import java.util.List;

public interface Strategy {
    void execute();
    void cleanup();
    boolean isFinished();

    void initAsset(Asset asset);
}
