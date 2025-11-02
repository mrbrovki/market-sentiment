package com.devbrovki.sentiment.controller;

import com.devbrovki.sentiment.dto.SentimentScore;
import com.devbrovki.sentiment.services.DecayedStore;
import com.devbrovki.sentiment.services.SentimentStore;
import jakarta.websocket.server.PathParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/assets")
public class AssetController {

    private final SentimentStore sentimentStore;
    private final DecayedStore decayedStore;

    public AssetController(SentimentStore sentimentStore, DecayedStore decayedStore) {
        this.sentimentStore = sentimentStore;
        this.decayedStore = decayedStore;
    }

    @GetMapping
    public ResponseEntity<List<String>> getAllAssets() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{asset}")
    public ResponseEntity<String> getAsset(@PathVariable String asset) {
        //provide summary
        return ResponseEntity.ok(asset);
    }

    @GetMapping("/{asset}/scores")
    public ResponseEntity<List<?>> getAssetScores(@PathVariable String asset, @RequestParam boolean decayed) {
        if(decayed){
            return ResponseEntity.ok(decayedStore.getDecayedScores(asset));
        }else{
            return ResponseEntity.ok(sentimentStore.getSentiments(asset));
        }
    }

    @GetMapping("/{asset}/params")
    public ResponseEntity<String> getAssetParams(@PathVariable String asset) {
        return ResponseEntity.ok(asset);
    }
}
