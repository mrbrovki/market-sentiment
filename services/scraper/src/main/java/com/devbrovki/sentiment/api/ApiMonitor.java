package com.devbrovki.sentiment.api;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Component
public class ApiMonitor {
    @Value("${investing.com.pages}")
    private int pages;

    private final ExecutorService executor;

    public List<Asset> assets = new LinkedList<>();

    private final ApplicationContext applicationContext;

    private final List<InvestingDotComStrategy> strategies = new ArrayList<>();

    public ApiMonitor(ApplicationContext applicationContext, ExecutorService executor) {
        this.applicationContext = applicationContext;
        this.executor = executor;
    }

    @PostConstruct
    private void init() {
        try {
            InputStream inputStream = new ClassPathResource("investingdotcom.csv").getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                Asset asset = Asset.builder()
                        .name(parts[0])
                        .url(parts[1])
                        .pages(pages)
                        .source(parts[3])
                        .build();
                this.assets.add(asset);

            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }


        for(Asset asset : this.assets) {
            InvestingDotComStrategy strategy = applicationContext.getBean(InvestingDotComStrategy.class);
            strategies.add(strategy);
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void monitorApis() {
        for (int i = 0; i < strategies.size(); i++) {
            int finalI = i;
            if(strategies.get(finalI).isFinished){
                executor.execute(() -> {
                    Context context = new Context();
                    context.setAsset(assets.get(finalI));
                    context.setPartition(finalI);
                    context.executeStrategy(strategies.get(finalI));
                });
            }
        }
    }
}