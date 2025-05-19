package com.devbrovki.sentiment.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ExecutorConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService assetExecutor() {
        // exactly 8 threads; additional tasks queue up
        return Executors.newFixedThreadPool(8);
    }
}