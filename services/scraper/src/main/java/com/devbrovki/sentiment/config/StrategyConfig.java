package com.devbrovki.sentiment.config;

import com.devbrovki.sentiment.api.Strategy;
import org.openqa.selenium.WebDriver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

@Configuration
public class StrategyConfig {

    @Bean
    public List<Strategy> strategies() {
        return new CopyOnWriteArrayList<>();
    }
}