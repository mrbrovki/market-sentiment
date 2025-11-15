package com.devbrovki.sentiment.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Component
public class ApiMonitor {
    private final ExecutorService executorService;
    private final List<Strategy> strategies;

    public ApiMonitor(ExecutorService executorService, List<Strategy> strategies) {
        this.executorService = executorService;
        this.strategies = strategies;
    }

    @Scheduled(fixedDelay = 3600_000, initialDelay = 120_000)
    public void monitorApis() {
        for (Strategy strategy : strategies) {
            executorService.submit(()->{
                try {
                    Thread.sleep(Math.round(Math.random() * 600_000));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if(strategy.isFinished()){
                    strategy.execute();
                }
            });
        }
    }

    //  once per day
    @Scheduled(fixedRate = 86_400_000, initialDelay = 28_800_000)
    private void cleanup(){
        for (Strategy strategy : strategies) {
            strategy.cleanup();
        }
    }
}