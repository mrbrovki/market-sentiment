package com.devbrovki.sentiment.api;

import com.devbrovki.sentiment.utils.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Simple retry handler with exponential backoff and network awareness.
 */

@Component
public class ResilienceManager {
    @Value("${PAGE_LOAD_MAX_RETRIES}")
    private int PAGE_LOAD_MAX_RETRIES;

    private final ExecutorService executorService;
    private static final long INITIAL_DELAY = 1_000;
    private static final long MAX_DELAY = 86_400_000;
    private volatile boolean networkDown = false;
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);
    private final HttpClient httpClient;

    @Autowired
    public ResilienceManager(ExecutorService executorService) {
        this.executorService = executorService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @PostConstruct
    public void init(){
        startNetworkMonitor();
    }

    @PreDestroy
    public void shutdown() {
        monitorRunning.set(false);
    }

    private void startNetworkMonitor() {
        monitorRunning.set(true);
        executorService.submit(() -> {
            while (monitorRunning.get()) {
                try {
                    networkDown = !canReachNetwork();
                    if(networkDown){
                        Thread.sleep(30_000);
                    }else{
                        Thread.sleep(300_000);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Continue monitoring
                }
            }
        });
    }

    private boolean canReachNetwork() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new java.net.URI("https://www.google.com"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param operation The operation to retry
     * @param logger Operation logger
     * @param shouldRetry Return false to skip retries (e.g., 404 Not Found)
     */
    public <T> T retry(Supplier<T> operation, Logger logger,
                       Function<Exception, Boolean> shouldRetry) {
        long delay = INITIAL_DELAY;
        int attempt = 0;

        while (attempt < PAGE_LOAD_MAX_RETRIES) {
            attempt++;

            while (networkDown) {
                logger.logWarn("Network down, waiting...");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            try {
                return operation.get();
            } catch (Exception e) {
                logger.logError("Attempt " + attempt + " failed", e);
                // Check if we should retry this error
                if (!shouldRetry.apply(e)) {
                    logger.logError("Non-retryable error, skipping retries", e);
                    return null;
                }

                try {
                    long waitTime = Math.min(delay, MAX_DELAY);
                    Thread.sleep(waitTime);
                    delay *= 2;
                } catch (InterruptedException e2) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        logger.logError("All attempts exhausted");
        return null;
    }
}