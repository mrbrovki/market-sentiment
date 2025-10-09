package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.model.Asset;
import com.devbrovki.sentiment.model.Event;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Scope("prototype")
public class InvestingDotComStrategy implements Strategy {
    @Value("${queue.init.time}")
    private long queueInitTime;

    private Asset asset;

    //  FLAGS
    private boolean isHistorical = true;
    private volatile boolean isFinished = true;
    private volatile boolean shutdown = false;

    //  BEANS
    private final ExecutorService executorService;
    private final KafkaTemplate<String, Event> kafkaTemplate;

    // ARTICLES
    private final Map<String, Long> readArticlesMap = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Event> linksQueue = new PriorityBlockingQueue<>(1024,
            Comparator.comparingLong(Event::getTimeStamp));
    private long lastValidTS = -1;
    private final long MAX_AGE = 14 * 86_400_000;


    //  LOGGING
    private BufferedWriter bufferedWriter = null;


    //  BROWSING
    @Value("${remote.web.driver}")
    private String remoteWebDriverUrl;
    private final List<FirefoxOptions> optionsList = new ArrayList<>(2);

    private final WebDriver[] drivers = new WebDriver[2];
    private void initDriver(int session) {
        try {
            drivers[session] = RemoteWebDriver.builder()
                    .oneOf(optionsList.get(session))
                    .address(new URI(remoteWebDriverUrl))
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        drivers[session].manage().timeouts().pageLoadTimeout(Duration.ofSeconds(240));
    }
    private void initDrivers(List<String> userAgents) {
        for (String userAgent: userAgents) {
            FirefoxOptions options = new FirefoxOptions();
            FirefoxProfile firefoxProfile = new FirefoxProfile();
            firefoxProfile.setPreference("general.useragent.override", userAgent);
            //options.setProfile(firefoxProfile);



            // Disable images
            options.addPreference("permissions.default.image", 2);
            options.addPreference("permissions.default.stylesheet", 2);  // no CSS
            options.addPreference("gfx.downloadable_fonts.enabled", false);// no custom fonts

            options.addArguments("-private");
            options.addArguments("-headless");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);

            optionsList.add(options);
        }

        for(int i = 0; i < drivers.length; i++) {
            initDriver(i);
        }
    }
    private void clearBrowserState(WebDriver driver) {
        try {
            driver.manage().deleteAllCookies();
            ((JavascriptExecutor) driver).executeScript("window.localStorage.clear(); window.sessionStorage.clear();");

            System.out.println(this.asset.getName() + " " + this.isHistorical + ": Cleared cookies and storage.");
        } catch (Exception e) {
            System.err.println(this.asset.getName() + ": Could not clear browser state: " + e.getMessage());
        }
    }
    private void reconnectDriver(int session) {
        WebDriver driver = drivers[session];
        executorService.submit(() -> {
            cleanupDriver(driver);
        });
        initDriver(session);

        try {
            Thread.sleep((long) (Math.random() * 10000));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    public void cleanupDriver(WebDriver driver) {
        if (driver != null) {
            driver.quit();
        }
    }

    @PreDestroy
    public void destroy() {
        shutdown = true;
        for (WebDriver driver : drivers) {
            if(driver != null) {
                driver.quit();
            }
        }
        try {
            bufferedWriter.flush();
            bufferedWriter.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    private String getPageSourceWithRetry(String url, int session, int maxRetries) {
        String pageSource = null;
        int attempt = 0;

        while (pageSource == null && attempt < maxRetries) {
            try {
                attempt++;
                drivers[session].get(url);
                pageSource = drivers[session].getPageSource();
                clearBrowserState(drivers[session]);
            } catch (Exception e) {
                System.err.println(this.asset.getName() + ": Driver error. Reconnecting...");
                reconnectDriver(session);
            }
        }

        return pageSource;
    }


    @Autowired
    public InvestingDotComStrategy(KafkaTemplate<String, Event> kafkaTemplate, ExecutorService executorService) {
        this.kafkaTemplate = kafkaTemplate;
        this.executorService = executorService;
    }

    public void init(Asset asset, List<String> userAgents){
        this.asset = asset;

        initDrivers(userAgents);
        processQueue();

        String csvPath = "db/" + this.asset.getName() + ".csv";
        loadReadArticlesFromCsvFile(csvPath);
        File csvFile = new File(csvPath);
        try {
            FileWriter csvWriter = new FileWriter(csvFile, true);
            bufferedWriter = new BufferedWriter(csvWriter, 16 * 1024);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
        Elements articles = getArticlesWithRetry(page, 10);
        createEvents(articles);
    }
    private void historical(int page) {
        Elements articles = getArticlesWithRetry(page, 20);

        Collections.reverse(articles);
        createEvents(articles);
    }
    private Elements getArticlesWithRetry(int page, int maxRetries) {
        int attempt = 0;
        Elements articles = new Elements();

        while (attempt < maxRetries) {
            attempt++;
            String url = asset.getUrl().replace("{page}", String.valueOf(page));
            Optional<String> pageSource = Optional.ofNullable(getPageSourceWithRetry(url, 0, 8));

            if(pageSource.isPresent()){
                Document document = Jsoup.parse(pageSource.get());
                articles = document.getElementsByTag("article");
            }

            if (!articles.isEmpty()) {
                break;
            }

            System.err.println(this.asset.getName() + ": Attempt " + (attempt) + " failed. Reconnecting...");
            reconnectDriver(0);
        }

        if (articles.isEmpty()) {
            System.err.println(this.asset.getName() + ": All attempts to get articles failed");
        }
        return articles;
    }
    private void createEvents(Elements articles){
        for (Element article : articles) {
            String link;

            try {
                link = getAbsoluteLink(article);
            }catch (Exception e){
                continue;
            }

            String title = getTitle(article);

            if (readArticlesMap.containsKey(link)) {
                System.out.println(link + " skipped!");
                continue;
            }

            long timestamp = getArticleTS(article);
            if (timestamp == -1) {
                System.err.println("dropped");
                continue;
            }

            if (timestamp > lastValidTS) {
                lastValidTS = timestamp;
            }

            Event event = new Event();
            event.setUrl(link);
            event.setTimeStamp(timestamp);
            event.setTitle(title);
            event.setSource(asset.getSource());
            event.setAsset(asset.getName());

            linksQueue.add(event);
            readArticlesMap.putIfAbsent(link, timestamp);

            if(!this.isHistorical){
                long now = System.currentTimeMillis();
                if(timestamp <= now - MAX_AGE){
                    this.isFinished = true;
                }
            }
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
                    handleArticle(event);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
    private void readArticleWithRetry(Event event, int maxRetries) {
        event.setContent("");
        int attempt = 0;
        int session = 1;
        Optional<String> pageSource = Optional.empty();

        while (attempt < maxRetries){
            attempt++;

            pageSource = Optional.ofNullable(getPageSourceWithRetry(event.getUrl(), session, 8));

            if(pageSource.isPresent()){
                break;
            }

            reconnectDriver(session);
        }

        if(pageSource.isEmpty()){
            System.err.println(this.asset.getName() + ": Failed to read article: " + event.getUrl());
            return;
        }

        Document document = Jsoup.parse(pageSource.get());

        // Extract the article title
        Element titleEl = document.getElementById("articleTitle");
        if (titleEl != null) {
            event.setTitle(titleEl.text());
        }
        // Extract the article content
        Element articleEl = document.getElementById("article");
        if (articleEl != null) {
            event.setContent(articleEl.text());
        }
    }
    private void handleArticle(Event event) {
        readArticleWithRetry(event, 12);

        event.setId(UUID.randomUUID().toString());

        if (event.getTimeStamp() > 0 && !event.getTitle().isEmpty()) {
            sendUpdateToKafka(event);
            persistEvent(event);
            readArticlesMap.replace(event.getUrl(), event.getTimeStamp());
        } else {
            System.err.println(this.asset.getName() + ": event dropped");
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
                            System.err.println("Invalid timestamp on line " + (count+1) + ": " + parts[1]);
                        }
                    } else {
                        System.err.println("Skipping malformed line " + (count+1) + ": " + line);
                    }
                }
                line = reader.readLine();
            }
            System.out.println("Loaded " + count + " entries from CSV file.");
            System.out.println("readArticlesMap now has " + readArticlesMap.size() + " entries.");
        } catch (IOException e) {
            System.err.println("Failed to read CSV '" + assetPath + "': " + e.getMessage());
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
            // Case 3: plain local date-time, assume site’s default zone
            LocalDateTime ldt = LocalDateTime.parse(dtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.of("America/New_York"))  // or asset.getZoneId()
                    .toInstant().toEpochMilli();
        } catch (Exception ignore) {}

        try {
            // Case 3: plain local date-time, assume site’s default zone
            LocalDateTime ldt = LocalDateTime.parse(dtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.of("America/New_York"))  // or asset.getZoneId()
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

        System.err.println(this.asset.getName() + ": couldn't parse datetime '" + dtStr + "'");
        return -1;
    }
    private void persistEvent(Event event){
        try {
            bufferedWriter.write(event.getUrl() + "," + event.getTimeStamp() + "\n");
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
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