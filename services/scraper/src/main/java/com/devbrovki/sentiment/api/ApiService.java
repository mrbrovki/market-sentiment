package com.devbrovki.sentiment.api;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ApiService {

    private final WebClient webClient;

    public ApiService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Mono<String> request(String apiUrl) {
        try {
            return webClient.get()
                    .uri(apiUrl)
                    .retrieve()
                    .bodyToMono(String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}