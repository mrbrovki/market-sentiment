package com.devbrovki.sentiment.api;

import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Component
public class ApiMonitor {
    private final ExecutorService executorService;
    public final List<Asset> assets = new LinkedList<>();
    private final ApplicationContext applicationContext;
    private final List<Strategy> strategies = new ArrayList<>();


    public ApiMonitor(ApplicationContext applicationContext, ExecutorService executorService) {
        this.applicationContext = applicationContext;
        this.executorService = executorService;
    }

    private void initInvestingDotCom(){
        try {
            InputStream inputStream = new ClassPathResource("investingdotcom.csv").getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            br.readLine();
            Context investingContext = new Context();

            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                Asset asset = Asset.builder()
                        .name(parts[0])
                        .url(parts[1])
                        .pages(Integer.parseInt(parts[2]))
                        .source(parts[3])
                        .build();
                this.assets.add(asset);

                InvestingDotComStrategy strategy =
                        applicationContext.getBean(InvestingDotComStrategy.class);

                investingContext.setAsset(asset);
                investingContext.init(strategy);

                strategies.add(strategy);
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    @PostConstruct
    private void init() {
        initInvestingDotCom();
    }

    @Scheduled(fixedDelay = 180_000, initialDelay = 0)
    public void monitorApis() {
        for (Strategy strategy : strategies) {
            executorService.submit(()->{
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