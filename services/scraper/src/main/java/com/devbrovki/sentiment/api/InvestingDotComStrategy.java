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
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Scope("prototype")
public class InvestingDotComStrategy implements Strategy{

    private final KafkaTemplate<String, Event> kafkaTemplate;
    FirefoxOptions options;

    private String lastArticleLink = null;

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

        this.driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
    }

    private boolean isHistorical = true;
    public volatile boolean isFinished  = true;


    @Override
    public void execute(Asset asset, int partition) {
        isFinished =  false;

        if(!isHistorical){
            for(int i = 1; i <= asset.getPages() && !isFinished; i++){
                isFinished = realtime(asset, i, partition);
            }
        }
        else{
            for(int i = asset.getPages(); i >= 1; i--) {
                 historical(asset, i, partition);
                if(i == 1){
                    isHistorical = false;
                    isFinished = true;
                }
            }
        }
    }

    private boolean realtime(Asset asset, int page, int partition) {
        Elements articles = getArticlesWithRetry(asset, page, 3);

        if(page == 1 && !articles.isEmpty()){
            this.lastArticleLink = getLinkInfo(articles.getFirst())[0];
        }

        for (Element article : articles) {
            String[] linkInfo = getLinkInfo(article);

            if(!linkInfo[0].equals(this.lastArticleLink)){
                handleArticle(article, partition, asset);
            }else{
                return true;
            }
        }

        return false;
    }

    private String[] getLinkInfo(Element article){
        String[] arr = new String[2];
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
        arr[0] = href;
        arr[1] = anchor.text();
        return arr;
    }

    private void historical(Asset asset, int page, int partition) {
        Elements articles = getArticlesWithRetry(asset, page, 5);

        Collections.reverse(articles);
        for (Element article : articles) {
            handleArticle(article, partition, asset);
        }

        if (!articles.isEmpty()) {
            this.lastArticleLink = getLinkInfo(articles.getLast())[0];
        }
    }

    private void sendUpdateToKafka(Event event, int partition, String key) {
       kafkaTemplate.send("events", partition, key, event);
    }

    private void readArticle(String url, Event event) {
        event.setContent("");
        event.setUrl(url);
        event.setTimeStamp(0);
        Elements spans = null;

        try {
            String pageSource = getPageSourceWithRetry(url);

            Document document = Jsoup.parse(pageSource);

            // Extract the article content
            Element articleEl = document.getElementById("article");
            if (articleEl != null) {
                event.setContent(articleEl.text());
            }

            // Parse the published date and time from spans
            spans = document.getElementsByTag("span");
        } catch (Exception e) {
            System.err.println("Failed to parse pageSource: " + e.getMessage());
        }

        if(spans == null) {return;}
        for (Element span : spans) {
            Pattern pattern1 = Pattern.compile("[A-Z][a-z][a-z] [0-9][0-9], [0-9]{4} [0-2][0-9]:[0-5][0-9](AM|PM)");
            Matcher matcher1 = pattern1.matcher(span.text());

            if(matcher1.find()) {
                try {
                    String date = matcher1.group();
                    System.out.println("Found date: " + date);

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
                }catch (DateTimeParseException e) {
                    System.err.println(Arrays.toString(span.text().toCharArray()));
                }
                break;
            }

            Pattern pattern2 = Pattern.compile("[A-Z][a-z][a-z] [0-9][0-9], [0-9]{4}");
            Matcher matcher2 = pattern2.matcher(span.text());

            if(matcher2.find()) {
                try {
                    String date = matcher2.group();
                    System.out.println("Found date: " + date);

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
                }catch (DateTimeParseException e) {
                    System.err.println(Arrays.toString(span.text().toCharArray()));
                }
                break;
            }

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
                    } catch (DateTimeParseException e) {
                        System.err.println("Failed to parse datetime: " + e.getMessage());
                    }
                } else {
                    System.err.println("Unexpected Published string format: " + span.text());
                }
                break;
            }

        }
    }

    private Elements getArticles(Asset asset, int page){
        String url = asset.getUrl().replace("{page}", String.valueOf(page));
        Elements articles;
        String pageSource = getPageSourceWithRetry(url);
        Document document = Jsoup.parse(pageSource);
        articles = document.getElementsByTag("article");
        return articles;
    }

    private Elements getArticlesWithRetry(Asset asset, int page, int maxRetries) {
        int attempt = 0;
        Elements articles = new Elements();

        while (articles.isEmpty() && attempt < maxRetries) {
            attempt++;
            articles = getArticles(asset, page);
            if (!articles.isEmpty()) {
                break;
            }
            System.err.println("Attempt " + (attempt) + " failed");
        }
        if (articles.isEmpty()) {
            System.err.println("All attempts to get articles failed");
        }
        return articles;
    }

    private String getPageSourceWithRetry(String url){
        String pageSource = null;
        while (pageSource == null) {
            try {
                driver.get(url);
                pageSource = driver.getPageSource();
            }catch (Exception e){
                System.err.println("Driver timed out. New session...");
                cleanupDriver();
                initDriver();
            }
        }

        clearBrowserState();

        return pageSource;
    }

    private void handleArticle(Element article, int partition, Asset asset) {
        Event event = new Event();
        String[] linkInfo = getLinkInfo(article);
        event.setTitle(linkInfo[1]);

        readArticle(linkInfo[0], event);

        event.setId(UUID.randomUUID().toString());

        sendUpdateToKafka(event, partition, asset.getSource());
    }

    private void clearBrowserState() {
        try {
            driver.manage().deleteAllCookies();
            ((JavascriptExecutor) driver).executeScript("window.localStorage.clear(); window.sessionStorage.clear();");
            System.out.println("Cleared cookies and storage.");
        } catch (Exception e) {
            System.err.println("Could not clear browser state: " + e.getMessage());
        }
    }
}