package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.model.Event;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

@Component
@Scope("prototype")
public class InvestingDotComStrategy implements Strategy{

    private final KafkaTemplate<String, Event> kafkaTemplate;
    FirefoxOptions options;

    private final Map<String, Long> readArticlesMap = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<Event> linksQueue = new PriorityBlockingQueue<>(1024,
            Comparator.comparingLong(Event::getTimeStamp));


    @Value("${remote.web.driver}")
    private String remoteWebDriverUrl;

    private WebDriver driver;

    @PreDestroy
    public void cleanupDriver() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Autowired
    public InvestingDotComStrategy(KafkaTemplate<String, Event>  kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void initDriver(){
        options = new FirefoxOptions();

        options.addArguments("-headless");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        // Disable images
        options.addPreference("permissions.default.image", 2);
        options.addPreference("permissions.default.stylesheet", 2);  // no CSS
        options.addPreference("gfx.downloadable_fonts.enabled", false);// no custom fonts

        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        try {
            this.driver = RemoteWebDriver.builder()
                    .oneOf(options)
                    .address(new URI(remoteWebDriverUrl))
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        this.driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
    }

    private boolean isHistorical = true;
    public volatile boolean isFinished  = true;

    private int partition;

    private long lastValidTS = -1;

    private Asset asset;

    @Override
    public void execute(Asset asset, int partition) {
        if(this.asset == null){
            this.asset = asset;
            this.partition = partition;
        }

        isFinished =  false;

        if(!isHistorical){
            for(int page = 1; page <= asset.getPages() && !isFinished; page++){
                isFinished = realtime(page);
            }
        }
        else{
            for(int page = asset.getPages(); page >= 1; page--) {
                 historical(page);
                if(page == 1){
                    isHistorical = false;
                    isFinished = true;
                }
            }
        }
    }

    private boolean realtime(int page) {
        Elements articles = getArticlesWithRetry(page, 5);
        long now = System.currentTimeMillis();
        long maxAge = 14 * 86_400_000;
        boolean done = false;

        for (Element article : articles) {
            String link = getAbsoluteLink(article);
            String title = getTitle(article);

            if(readArticlesMap.containsKey(link)){
               continue;
            }

            long timestamp = getArticleTS(article);
            if(timestamp == -1){
                System.err.println("dropped");
                continue;
            }

            if(timestamp > lastValidTS){
                lastValidTS = timestamp;
            }

            Event event = new Event();
            event.setUrl(link);
            event.setTimeStamp(timestamp);
            event.setTitle(title);

            linksQueue.add(event);
            readArticlesMap.put(link, timestamp);


            if(timestamp <= now - maxAge){
                done = true;
            }
        }

        return done;
    }

    private String getAbsoluteLink(Element article){
        String baseUrl = "https://www.investing.com";
        Elements anchors = article.getElementsByTag("a");
        if (anchors.isEmpty()){
            throw new RuntimeException("No links found");
        }
        Element anchor = anchors.getFirst();
        String href = anchor.attr("href");
        if (!href.startsWith("http")) {
            href = baseUrl + href;
        }
        return href;
    }

    private String getTitle(Element article) {
        String title = "";
        Elements anchors =
            article.getElementsByAttributeValueStarting("data-test", "article-title-link");
        if (!anchors.isEmpty()){
            Element anchor = anchors.getFirst();
            title = anchor.text();
        }

        return title;
    }

    private long getArticleTS(Element article){
        long timestamp = -1;

        try {
            Elements timeElements = article.getElementsByTag("time");
            if(timeElements.isEmpty()){
                throw new RuntimeException();
            }

            Element timeTag = timeElements.getFirst();
            String dtStr = timeTag.attr("datetime");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime dateTime = LocalDateTime.parse(dtStr, formatter);

            timestamp= dateTime
                    .atZone(ZoneId.of("America/New_York"))
                    .toInstant()
                    .toEpochMilli();
        }catch (Exception e){
            System.err.println(this.asset.getName() + ": DATE TIME DIDN'T PARSE");
        }

        return timestamp;
    }

    private void historical(int page) {
        Elements articles = getArticlesWithRetry(page, 5);

        Collections.reverse(articles);
        for (Element article : articles) {
            String link = getAbsoluteLink(article);
            String title = getTitle(article);
            if(readArticlesMap.containsKey(link)){
                continue;
            }

            long timestamp = getArticleTS(article);
            if(timestamp == -1){
                System.err.println("dropped");
                continue;
            }

            if(timestamp > lastValidTS){
                lastValidTS = timestamp;
            }

            Event event = new Event();
            event.setUrl(link);
            event.setTimeStamp(timestamp);
            event.setTitle(title);

            linksQueue.add(event);
            readArticlesMap.put(link, timestamp);
        }
    }

    private void sendUpdateToKafka(Event event) {
       kafkaTemplate.send("events", partition, event.getId(), event);
    }

    private void readArticle(Event event) {
        event.setContent("");

        try {
            String pageSource = getPageSourceWithRetry(event.getUrl(), 5);

            Document document = Jsoup.parse(pageSource);

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

            // Parse the published date and time from spans
            //Elements spans = document.getElementsByTag("span");
        } catch (Exception e) {
            System.err.println(this.asset.getName() + ": Failed to parse pageSource: " + e.getMessage());
            event.setTimeStamp(-1);
        }

         /*
            if(spans != null) {
                for (Element span : spans) {
                    if (span.text().startsWith("Published")) {
                        String[] parts = span.text().split(" ");
                        if (parts.length >= 4) {
                            try {
                                // Remove the comma from the date portion
                                String datePart = parts[1].replace(",", "");
                                String timeString = datePart + " " + parts[2] + " " + parts[3];

                                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a", Locale.US);
                                LocalDateTime dateTime = LocalDateTime.parse(timeString, formatter);

                                long timestamp = dateTime
                                        .atZone(ZoneId.of("America/New_York"))
                                        .toInstant()
                                        .toEpochMilli();

                                event.setTimeStamp(timestamp);
                                done = true;
                            } catch (DateTimeParseException e) {
                                System.err.println(this.asset.getName() + ": Failed to parse datetime: " + e.getMessage());
                            }
                        } else {
                            System.err.println(this.asset.getName() + ": Unexpected Published string format: " + span.text());
                        }
                        break;
                    }

                    Pattern pattern1 = Pattern.compile("[A-Z][a-z][a-z] [0-9][0-9], [0-9]{4} [0-2][0-9]:[0-5][0-9](AM|PM)");
                    Matcher matcher1 = pattern1.matcher(span.text());

                    if (matcher1.find()) {
                        try {
                            String date = matcher1.group();
                            System.out.println(this.asset.getName() + ": Found date: " + date);

                            // Define the formatter
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mma", Locale.US);

                            // Parse the date
                            LocalDateTime dateTime = LocalDateTime.parse(date, formatter);

                            // Convert to epoch milliseconds (start of day in specified time zone)
                            long timestamp = dateTime
                                    .atZone(ZoneId.of("America/New_York"))
                                    .toInstant()
                                    .toEpochMilli();

                            event.setTimeStamp(timestamp);
                            done = true;
                        } catch (DateTimeParseException e) {
                            System.err.println(Arrays.toString(span.text().toCharArray()));
                        }
                        break;
                    }

                    Pattern pattern2 = Pattern.compile("[A-Z][a-z][a-z] [0-9][0-9], [0-9]{4}");
                    Matcher matcher2 = pattern2.matcher(span.text());

                    if (matcher2.find()) {
                        try {
                            String date = matcher2.group();
                            System.out.println(this.asset.getName() + ": Found date: " + date);

                            // Define the formatter
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US);

                            // Parse the date
                            LocalDate localDate = LocalDate.parse(date, formatter);

                            // Convert to epoch milliseconds (start of day in specified time zone)
                            long timestamp = localDate
                                    .atStartOfDay(ZoneId.of("America/New_York"))
                                    .toInstant()
                                    .toEpochMilli();

                            event.setTimeStamp(timestamp);
                            done = true;
                        } catch (DateTimeParseException e) {
                            System.err.println(Arrays.toString(span.text().toCharArray()));
                        }
                        break;
                    }
                }
            }

             */
    }

    private Elements getArticles(int page){
        String url = asset.getUrl().replace("{page}", String.valueOf(page));
        Elements articles;
        try {
            String pageSource = getPageSourceWithRetry(url,5);
            Document document = Jsoup.parse(pageSource);
            articles = document.getElementsByTag("article");
        }catch (Exception e){
            System.err.println(this.asset.getName() + ": Failed to parse pageSource: " + e.getMessage());
            return new Elements();
        }

        return articles;
    }

    private Elements getArticlesWithRetry(int page, int maxRetries) {
        int attempt = 0;
        Elements articles = new Elements();

        while (articles.isEmpty() && attempt < maxRetries) {
            attempt++;
            articles = getArticles(page);
            if (!articles.isEmpty()) {
                break;
            }
            System.err.println(this.asset.getName() + ": Attempt " + (attempt) + " failed");
        }
        if (articles.isEmpty()) {
            System.err.println(this.asset.getName() + ": All attempts to get articles failed");
        }
        return articles;
    }

    private synchronized String getPageSourceWithRetry(String url, int maxRetries) {
        String pageSource = null;
        int attempt = 0;

        while (pageSource == null && attempt < maxRetries) {
            try {
                attempt++;
                driver.get(url);
                pageSource = driver.getPageSource();
            }catch (Exception e){
                System.err.println(this.asset.getName() + ": Driver timed out. New session...");
                cleanupDriver();
                initDriver();
            }
        }

        clearBrowserState();

        return pageSource;
    }

    private void handleArticle(Event event) {
        readArticle(event);

        event.setId(UUID.randomUUID().toString());

        if(event.getTimeStamp() != -1 && !event.getTitle().isEmpty()){
            sendUpdateToKafka(event);
            readArticlesMap.replace(event.getUrl(), event.getTimeStamp());
        }else{
            System.err.println(this.asset.getName() + ": event dropped");
        }
    }

    private void clearBrowserState() {
        try {
            driver.manage().deleteAllCookies();
            ((JavascriptExecutor) driver).executeScript("window.localStorage.clear(); window.sessionStorage.clear();");
            System.out.println(this.asset.getName() + ": Cleared cookies and storage.");
        } catch (Exception e) {
            System.err.println(this.asset.getName() + ": Could not clear browser state: " + e.getMessage());
        }
    }

    public void cleanup() {
        long now = lastValidTS;
        long threshold = now - (31L * 86_400_000); // 31 days in millis
        readArticlesMap.entrySet().removeIf(entry -> (entry.getValue() < threshold && entry.getValue() != -1));
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 3600_000)
    private void processQueue(){
        int numHandled = 0;

        while (!linksQueue.isEmpty() && (++numHandled < 32) && !isHistorical) {
            Event event = linksQueue.poll();
            handleArticle(event);
        }
    }
}