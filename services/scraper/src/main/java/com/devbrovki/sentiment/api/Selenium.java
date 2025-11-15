package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.utils.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class Selenium {
    @Value("${MAX_SESSIONS}")
    private int MAX_SESSIONS;

    private final AtomicInteger activeSessions = new AtomicInteger(0);

    // Flag to control populate loop and allow clean shutdown
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Value("${remote.web.driver}")
    private String remoteWebDriverUrl;
    private final FirefoxOptions options = new FirefoxOptions();

    private BlockingQueue<WebDriver> sessionPool;
    private final ExecutorService executorService;
    private final Logger logger;

    @Autowired
    public Selenium(ExecutorService executorService, Logger logger) {
        this.executorService = executorService;
        this.logger = logger;
    }

    @PostConstruct
    private void init() {
        this.sessionPool = new LinkedBlockingQueue<>(MAX_SESSIONS);
        initOptions();

        executorService.submit(this::populateSessionPool);
    }

    @PreDestroy
    public void destroy() {
        // stop population thread
        running.set(false);
        // drain and cleanup all drivers
        List<WebDriver> drivers = new ArrayList<>();
        sessionPool.drainTo(drivers);
        for (WebDriver driver : drivers) {
            cleanupDriver(driver);
        }
        sessionPool.clear();
    }

    private void populateSessionPool(){
        long backoff = 5_000L; // initial backoff 5s
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (activeSessions.get() < MAX_SESSIONS) {
                    boolean success = addSessionToPool();
                    if (success) {
                        activeSessions.incrementAndGet();
                        backoff = 5_000L; // reset on success
                    } else {
                        // apply capped exponential backoff with jitter
                        Thread.sleep(backoff + (long)(Math.random() * 1_000));
                        backoff = Math.min(backoff * 2, 60_000L);
                    }
                } else {
                    Thread.sleep(5_000L);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void initOptions() {
        // Disable images
        options.addPreference("permissions.default.image", 2);
        options.addPreference("permissions.default.stylesheet", 2);  // no CSS
        options.addPreference("gfx.downloadable_fonts.enabled", false);// no custom fonts

        // Hide automation signals
        options.addPreference("dom.webdriver.enabled", false);
        options.addPreference("useAutomationExtension", false);
        options.setCapability("unhandledPromptBehavior", "dismiss");

        options.addArguments("-private");
        options.addArguments("-headless");
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.setPageLoadTimeout(Duration.ofSeconds(180));
    }

    private boolean addSessionToPool() {
        try {
            WebDriver driver = RemoteWebDriver.builder()
                    .oneOf(options)
                    .address(URI.create(remoteWebDriverUrl))
                    .build();
            sessionPool.put(driver);
            return true;
        } catch (InterruptedException | WebDriverException e) {
            logger.logWarn("Failed to add driver to pool");
            return false;
        }
    }

    // synchronous driver creation used as fallback
    private WebDriver createDriverSync() {
        try {
            return RemoteWebDriver.builder()
                    .oneOf(options)
                    .address(URI.create(remoteWebDriverUrl))
                    .build();
        } catch (WebDriverException e) {
            logger.logError("Failed to create driver synchronously", e);
            return null;
        }
    }

    private void cleanupDriver(WebDriver driver) {
        try{
            if(driver != null) driver.quit();
        }catch (WebDriverException e){
            logger.logWarn("Could not cleanup driver");
        }finally {
            // ensure counter doesn't go negative
            activeSessions.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private WebDriver getDriverFromPool() {
        try {
            WebDriver driver = sessionPool.poll(60, TimeUnit.SECONDS);
            if (driver == null) {
                logger.logWarn("Timed out waiting for driver from pool after 60s");
            }
            return driver;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.logWarn("Interrupted while waiting for driver from pool");
            return null;
        }
    }

    public WebDriver replaceDriver(WebDriver oldDriver) {
        executorService.submit(()->{
            cleanupDriver(oldDriver);
        });
        WebDriver driver = getDriverFromPool();
        if(driver == null) {
            driver = createDriverSync();
        }
        return driver;
    }

    public void clearBrowserState(WebDriver driver) {
        try {
            driver.manage().deleteAllCookies();
            ((JavascriptExecutor) driver).executeScript("window.localStorage.clear(); window.sessionStorage.clear();");

            //logger.logInfo(String.format("%s %s: Cleared cookies and storage.", (asset != null ? asset.getName() : "N/A"), this.isHistorical));
        } catch (Exception e) {
            logger.logError("Could not clear browser state: " + e.getMessage(), e);
        }
    }
}
