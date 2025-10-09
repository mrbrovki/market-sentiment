package com.devbrovki.sentiment.config;

import com.devbrovki.sentiment.api.Strategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Configuration
public class StrategyConfig {

    @Bean
    public List<Strategy> strategies() {
        return new CopyOnWriteArrayList<>();
    }
}