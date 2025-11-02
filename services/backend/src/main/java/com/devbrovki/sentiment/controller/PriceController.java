package com.devbrovki.sentiment.controller;

import com.devbrovki.sentiment.dto.PriceCandle;
import com.devbrovki.sentiment.dto.PriceData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/price")
public class PriceController {

    @GetMapping("/polygon")
    public String getPolygonPriceData(@RequestParam("asset") String asset,
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

    @GetMapping("/yahoo")
    public ResponseEntity<PriceData> getYahooPriceData(
            @RequestParam("asset") String asset,
            @RequestParam("startTS") long startTS,
            @RequestParam("endTS") long endTS,
            @RequestParam String interval
    ) throws JsonProcessingException {

        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + asset + "?" +
                "interval=" + interval +
                "&period1=" + startTS +
                "&period2=" + endTS +
                "&includePrePost=true";

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode resultNode = mapper.readTree(response.body())
                .path("chart")
                .path("result")
                .get(0);

        ArrayNode timestamps = (ArrayNode) resultNode.path("timestamp");
        JsonNode quote = resultNode.path("indicators").path("quote").get(0);

        ArrayNode opens = (ArrayNode) quote.path("open");
        ArrayNode closes = (ArrayNode) quote.path("close");
        ArrayNode lows = (ArrayNode) quote.path("low");
        ArrayNode highs = (ArrayNode) quote.path("high");

        List<PriceCandle> candles = new ArrayList<>();

        for (int i = 0; i < timestamps.size(); i++) {
            if (opens.get(i).isNull() || closes.get(i).isNull() ||
                    lows.get(i).isNull() || highs.get(i).isNull()) {
                continue; // skip incomplete data points
            }

            candles.add(new PriceCandle(
                    opens.get(i).asDouble(),
                    closes.get(i).asDouble(),
                    lows.get(i).asDouble(),
                    highs.get(i).asDouble(),
                    timestamps.get(i).asLong()
            ));
        }

        PriceData priceData = new PriceData(
                asset,
                interval,
                startTS,
                endTS,
                candles
        );

        return ResponseEntity.ok(priceData);
    }
}
