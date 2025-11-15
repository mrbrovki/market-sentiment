package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.model.Asset;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@NoArgsConstructor
public class Context {
    private Asset asset;

    public void init(Strategy strategy){
        strategy.initAsset(asset);
    }
}
