package com.devbrovki.sentiment.utils;

import com.devbrovki.sentiment.model.Asset;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Scope("prototype")
public class Logger {
    private Asset asset;
    private String scraper;

    // Structured logging helpers: fixed JSON-like structure, includes asset name
    public void logInfo(String message) { log("INFO", message, null); }
    public void logWarn(String message) { log("WARN", message, null); }
    public void logError(String message) { log("ERROR", message, null); }
    public void logError(String message, Throwable t) { log("ERROR", message, t); }

    private void log(String level, String message, Throwable t) {
        String assetName = (asset != null && asset.getName() != null) ? asset.getName() : "N/A";
        String ts = Instant.now().toString();
        String safeMessage = message == null ? "" : message.replace("\"", "\\\"");
        String logLine = String.format("{\"ts\":\"%s\",\"level\":\"%s\",\"scraper\":\"%s\",\"asset\":\"%s\",\"message\":\"%s\"}",
                ts, level, scraper, assetName, safeMessage);

        if ("ERROR".equals(level) || "WARN".equals(level)) {
            System.err.println(logLine);
            if (t != null) t.printStackTrace(System.err);
        } else {
            System.out.println(logLine);
        }
    }

    public void setAsset(Asset asset) {
        this.asset = asset;
    }

    public void setScraper(String scraper) {
        this.scraper = scraper;
    }
}
