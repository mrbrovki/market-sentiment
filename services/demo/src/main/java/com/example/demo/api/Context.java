package com.example.demo.api;

import lombok.Setter;

@Setter
public class Context {
    private String url;
    private String coin;

    public void executeStrategy(Strategy strategy) {
        strategy.execute(url, coin);
    }
}
