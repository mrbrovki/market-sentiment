package com.example.demo.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApiMonitor {
    @Autowired
    private CryptoPanicStrategy cryptoPanicStrategy;

    @Value("${urls}")
    private String[] urls;

    @Value("${coins}")
    private String[] coins;

    @Scheduled(fixedRate = 10000)
    public void monitorApis() {
            for(String url : urls) {
                for (String coin : coins) {
                    Context context = new Context();
                    context.setUrl(url);
                    context.setCoin(coin);
                    context.executeStrategy(cryptoPanicStrategy);
                }
            }
    }
}