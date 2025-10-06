package com.devbrovki.sentiment.controller;

import com.devbrovki.sentiment.BackendApplication;
import com.devbrovki.sentiment.model.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@RestController
public class AssetController{

    @Autowired
    private Map<String, List<DeferredResult<List<Event>>>> waiters;


    @GetMapping("/assets/{asset}/{denom}")
    public DeferredResult<List<Event>> getAsset(@PathVariable String asset, @PathVariable String denom, @RequestParam int id) {
        DeferredResult<List<Event>> deferredResult = new DeferredResult<>(0_100L);

        String key = id + "_" + denom;

        // When timeout happens, remove the client from the queue
        deferredResult.onTimeout(() -> {
            deferredResult.setResult(BackendApplication.events[id].get(denom));
            waiters.get(key).remove(deferredResult);
        });

        // Remove after completion
        deferredResult.onCompletion(() -> {
            waiters.get(key).remove(deferredResult);
        });


        // Add this client to the queue
        waiters.computeIfAbsent(key, k -> new ArrayList<>());

        waiters.get(key).add(deferredResult);

        return deferredResult;
    }

    @GetMapping("/learn/assets/{asset}/{denom}")
    public List<Event> getLearningAsset(@PathVariable String asset, @PathVariable String denom, @RequestParam int id) {
        List<Event> assetEvents = BackendApplication.events[id].get(denom);
        return assetEvents.subList(0, (int) (assetEvents.size() * 0.8));
    }

    @GetMapping("/test/assets/{asset}/{denom}")
    public List<Event> getTestingAsset(@PathVariable String asset, @PathVariable String denom, @RequestParam int id) {
        List<Event> assetEvents = BackendApplication.events[id].get(denom);
        return assetEvents.subList((int) (assetEvents.size() * 0.8), assetEvents.size());
    }

    @GetMapping("/denoms")
    public Set<String> getAsset() {
        return BackendApplication.events[0].keySet();
    }

/*
    @GetMapping("/price/{asset}")
    public String getPrice(@PathVariable String asset, @RequestParam("startTS") long startTS, @RequestParam("endTS") long endTS,
                                                @RequestParam String interval) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + asset + "?" + "interval=" + interval +
                "&period1=" + startTS + "&period2=" + endTS + "&includePrePost=true";

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            response = null;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
        return  response.body();
    }

 */
    @GetMapping("/price/{asset}")
    public String getPrice(@PathVariable String asset,
                           @RequestParam("startTS") long startTS,
                           @RequestParam("endTS") long endTS,
                           @RequestParam String interval) {

        String apiKey = "";

        String baseUrl = String.format(
                "https://api.polygon.io/v2/aggs/ticker/%s/range/%s/%d/%d?adjusted=true&sort=asc&limit=50000&apiKey=%s",
                asset, interval, startTS, endTS, apiKey
        );

        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode allResults = mapper.createArrayNode(); // Jackson's array
        String nextUrl = baseUrl;

        try {
            while (nextUrl != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(nextUrl))
                        .header("User-Agent", "Java HttpClient")
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to fetch data: " + response.body());
                }

                JsonNode json = mapper.readTree(response.body());

                if (json.has("results") && json.get("results").isArray()) {
                    for (JsonNode result : json.get("results")) {
                        allResults.add(result);
                    }
                }

                if (json.has("next_url") && !json.get("next_url").isNull()) {
                    nextUrl = json.get("next_url").asText() + "&apiKey=" + apiKey;
                } else {
                    nextUrl = null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching data from Polygon.io", e);
        }

        ObjectNode resultWrapper = mapper.createObjectNode();
        resultWrapper.put("ticker", asset);
        resultWrapper.put("interval", interval);
        resultWrapper.put("startTS", startTS);
        resultWrapper.put("endTS", endTS);
        resultWrapper.set("results", allResults);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultWrapper);
        } catch (Exception e) {
            throw new RuntimeException("Error writing JSON output", e);
        }
    }
}
