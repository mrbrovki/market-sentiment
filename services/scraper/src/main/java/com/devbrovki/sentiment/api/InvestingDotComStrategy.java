package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.model.Asset;
import com.devbrovki.sentiment.model.Event;
import com.devbrovki.sentiment.utils.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Component
@Scope("prototype")
public class InvestingDotComStrategy implements Strategy {
    private final ResilienceManager resilienceManager;
    private final Logger logger;
    private final Selenium selenium;
    private final ExecutorService executorService;
    private final KafkaTemplate<String, Event> kafkaTemplate;

    @Value("${queue.init.time}")
    private long queueInitTime;

    private Asset asset;

    //  FLAGS
    private boolean isHistorical = true;
    private volatile boolean isFinished = true;
    private volatile boolean shutdown = false;

    // ARTICLES
    private final Map<String, Long> readArticlesMap = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Event> linksQueue =
            new PriorityBlockingQueue<>(1024, Comparator.comparingLong(Event::getTimestamp));
    private long lastValidTS = -1;
    private final long MAX_AGE = 14 * 86_400_000L;

    private final WebDriver[] drivers = new WebDriver[2];

    //  LOGGING
    private BufferedWriter bufferedWriter = null;

    private void newDriverSession(int driverIndex) {
        logger.logInfo("Creating new driver session for index: " + driverIndex);
        WebDriver oldDriver = drivers[driverIndex];
        drivers[driverIndex] = selenium.replaceDriver(oldDriver);
    }
    private String getPageSourceWithRetry(String url, int driverIndex) {
        return resilienceManager.retry(()->{
            newDriverSession(driverIndex);
            WebDriver driver = drivers[driverIndex];
            if (driver == null) {
                throw new RuntimeException("Driver is null after replacement");
            }
            driver.get(url);
            return driver.getPageSource();
        }, logger, exception -> {
            // Don't retry if page not found
            if (exception instanceof org.openqa.selenium.NoSuchElementException ||
                    exception.getMessage().contains("404")) {
                return false;
            }
            return true;
        });
    }

    @PreDestroy
    public void destroy() {
        shutdown = true;
        for (WebDriver driver : drivers) {
            try {
                if(driver != null) {
                    driver.quit();
                }
            }catch (WebDriverException e) {
                logger.logError("Could not quit driver when exiting", e);
            }
        }
        try {
            bufferedWriter.flush();
            bufferedWriter.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Autowired
    public InvestingDotComStrategy(KafkaTemplate<String, Event> kafkaTemplate, ExecutorService executorService,
                                   ResilienceManager resilienceManager, Logger logger, Selenium selenium) {
        this.kafkaTemplate = kafkaTemplate;
        this.executorService = executorService;
        this.resilienceManager = resilienceManager;
        this.logger = logger;
        this.selenium = selenium;
    }

    public void initAsset(Asset asset){
        this.asset = asset;

        logger.setAsset(asset);
        logger.setScraper("InvestingDotCom");

        String csvPath = "db/" + this.asset.getName() + ".csv";
        File csvFile = new File(csvPath);
        if(csvFile.exists()){
            loadReadArticlesFromCsvFile(csvPath);
        }else{
            csvFile.getParentFile().mkdirs();
            try {
                csvFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            FileWriter csvWriter = new FileWriter(csvFile, true);
            bufferedWriter = new BufferedWriter(csvWriter, 16 * 1024);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostConstruct
    public void init(){
        processQueue();
    }

    //  retrieve new articles
    @Override
    public void execute() {
        isFinished = false;

        if (!isHistorical) {
            for (int page = 1; page <= asset.getPages() && !isFinished; page++) {
                realtime(page);
            }
        } else {
            for (int page = asset.getPages(); page >= 1; page--) {
                historical(page);
                if (page == 1) {
                    isHistorical = false;
                    isFinished = true;
                }
            }
        }
    }
    private void realtime(int page) {
        Elements articles = getArticlesWithRetry(page, 12);
        logger.logInfo("doing realtime Page " + page + ": " + articles.size());
        handleArticles(articles);
    }
    private void historical(int page) {
        Elements articles = getArticlesWithRetry(page, 12);
        logger.logInfo("doing historical Page " + page + ": " + articles.size());

        Collections.reverse(articles);
        handleArticles(articles);
    }
    private Elements getArticlesWithRetry(int page, int maxRetries) {
        int attempt = 0;
        int driverIndex = 0;
        Elements articles = new Elements();

        while (attempt < maxRetries) {
            attempt++;
            String url = asset.getUrl().replace("{page}", String.valueOf(page));
            Optional<String> pageSource = Optional.ofNullable(getPageSourceWithRetry(url, driverIndex));

            if(pageSource.isPresent()){
                Document document = Jsoup.parse(pageSource.get());
                articles = document.getElementsByTag("article");
                if(!articles.isEmpty()) {
                    logger.logInfo("Articles read from page: " + page + ", count: " + articles.size());
                    break;
                }
                if(!document.getElementsByTag("error404").isEmpty()){
                    logger.logWarn("Page " + page + " failed to load articles. Error 404. Skipping reattempt...");
                    break;
                }
            }
            logger.logWarn("Page " + page + " failed to load articles. Attempt: " + attempt);
            try {
                Thread.sleep((int) Math.pow(2, attempt) * 1000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (articles.isEmpty()) {
            logger.logWarn("Page " + page + " failed to return articles");
        }
        return articles;
    }
    private void handleArticles(Elements articles){
        for (Element article : articles) {
            String link;

            try {
                link = getAbsoluteLink(article);
            }catch (Exception e){
                logger.logError("Could not get link: " + e.getMessage(), e);
                continue;
            }

            String title = getTitle(article);
            long timestamp = getArticleTS(article);

            if(!this.isHistorical){
                long now = System.currentTimeMillis();
                if(timestamp <= now - MAX_AGE){
                    this.isFinished = true;
                }
            }

            if (readArticlesMap.containsKey(link)) {
                logger.logInfo("Skipped already read link: " + link);
                continue;
            }

            if (timestamp == -1) {
                logger.logWarn("Article dropped due to invalid timestamp for link: " + link);
                continue;
            }

            if (timestamp > lastValidTS) {
                lastValidTS = timestamp;
            }

            Event event = new Event();
            event.setUrl(link);
            event.setTimestamp(timestamp);
            event.setTitle(title);
            event.setSource(asset.getSource());
            event.setAsset(asset.getName());

            linksQueue.add(event);
            readArticlesMap.putIfAbsent(link, timestamp);
        }
    }


    //  READING ARTICLES
    private void processQueue() {
        executorService.submit(()->{
            try {
                Thread.sleep(queueInitTime);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            while (!shutdown || !linksQueue.isEmpty()) {
                Event event = null;
                try {
                    event = linksQueue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if(event != null){
                    handleEvent(event);
                }
            }
        });
    }
    private void readArticleWithRetry(Event event, int maxRetries) {
        event.setContent("");
        int attempt = 0;
        int driverIndex = 1;
        Element titleEl = null;

        while (attempt < maxRetries){
            attempt++;

            Optional<String> pageSource = Optional.ofNullable(getPageSourceWithRetry(event.getUrl(), driverIndex));

            if(pageSource.isPresent()){
                Document document = Jsoup.parse(pageSource.get());

                // Extract the article content
                Element articleEl = document.getElementById("article");
                if (articleEl != null) {
                    event.setContent(articleEl.text());
                }

                // Extract the article title
                titleEl = document.getElementById("articleTitle");
                if (titleEl != null) {
                    event.setTitle(titleEl.text());
                    break;
                }

                if(!document.getElementsByTag("error404").isEmpty()){
                    logger.logWarn("Article " + event.getUrl() + " read failed. Error 404. Skipping reattempt...");
                    break;
                }
            }

            logger.logWarn("Article " + event.getUrl() + " read failed. Attempt: " + attempt);
            try {
                Thread.sleep((int) Math.pow(2, attempt) * 1000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if(titleEl == null){
            logger.logWarn("Failed to read article: " + event.getUrl());
        }
    }
    private void handleEvent(Event event) {
        readArticleWithRetry(event, 12);

        event.setId(UUID.randomUUID().toString());

        if (event.getTimestamp() > 0 && !event.getTitle().isEmpty()) {
            sendUpdateToKafka(event);
            persistEvent(event);
            readArticlesMap.replace(event.getUrl(), event.getTimestamp());
            logger.logInfo("Successfully read article: " + event.getUrl());
        } else {
            logger.logWarn("Event dropped: " + event.getUrl());
        }
    }
    private void loadReadArticlesFromCsvFile(String assetPath) {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new FileReader(assetPath, StandardCharsets.UTF_8)))
        {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        String url = parts[0].trim();
                        try {
                            long ts = Long.parseLong(parts[1].trim());
                            readArticlesMap.putIfAbsent(url, ts);
                            count++;
                        } catch (NumberFormatException nfe) {
                            logger.logWarn("Invalid timestamp on line " + (count+1) + ": " + parts[1]);
                        }
                    } else {
                        logger.logWarn("Skipping malformed line " + (count+1) + ": " + line);
                    }
                }
                line = reader.readLine();
            }
            logger.logInfo("Loaded " + count + " entries from CSV file.");
            logger.logInfo("readArticlesMap now has " + readArticlesMap.size() + " entries.");
        } catch (IOException e) {
            logger.logError("Failed to read CSV '" + assetPath + "': " + e.getMessage(), e);
        }
    }


    //  utils
    private String getAbsoluteLink(Element article) {
        Element a = article.selectFirst("a[data-test^=article-title-link]");
        if (a == null) throw new RuntimeException("No article link found");

        String href = a.attr("href");
        if (!href.startsWith("http")) {
            href = asset.getSource() + href;
        }
        return href;
    }
    private String getTitle(Element article) {
        String title = "";
        Elements anchors =
                article.getElementsByAttributeValueStarting("data-test", "article-title-link");
        if (!anchors.isEmpty()) {
            Element anchor = anchors.getFirst();
            title = anchor.text();
        }

        return title;
    }
    private long getArticleTS(Element article) {
        Element timeTag = article.getElementsByTag("time").first();
        if (timeTag == null) {
            return -1;
        }

        String dtStr = timeTag.attr("datetime").trim();
        if (dtStr.isEmpty()) {
            return -1;
        }

        try {
            // Case 1: full ISO with zone or offset, e.g. 2025-08-27T14:30:00Z
            return java.time.Instant.parse(dtStr).toEpochMilli();
        } catch (Exception ignore) {}

        try {
            // Case 2: ISO date-time with offset, e.g. 2025-08-27T14:30:00-04:00
            return java.time.OffsetDateTime.parse(dtStr, DateTimeFormatter.ISO_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception ignore) {}

        try {
            // Case 3: plain local date-time, assume site's default zone
            LocalDateTime ldt = LocalDateTime.parse(dtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.of("America/New_York"))
                    .toInstant().toEpochMilli();
        } catch (Exception ignore) {}

        try{
            // Case 4: yyyy-MM-dd HH:mm:ss format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime dateTime = LocalDateTime.parse(dtStr, formatter);
            return dateTime.atZone(ZoneId.of("America/New_York"))
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ignore) {}

        logger.logWarn("Couldn't parse datetime '" + dtStr + "'");
        return -1;
    }
    private void persistEvent(Event event){
        try {
            bufferedWriter.write(event.getUrl() + "," + event.getTimestamp() + "\n");
        } catch (IOException e) {
            logger.logError("Failed to persist event: " + e.getMessage(), e);
        }
    }
    private void sendUpdateToKafka(Event event){
        kafkaTemplate.send("events", event.getAsset(), event);
    }

    @Override
    public void cleanup() {
        long now = lastValidTS;
        long threshold = now - (31L * 86_400_000); // 31 days in millis
        readArticlesMap.entrySet().removeIf(entry -> (entry.getValue() < threshold && entry.getValue() != -1));
    }

    @Override
    public boolean isFinished() {
        return this.isFinished;
    }
}