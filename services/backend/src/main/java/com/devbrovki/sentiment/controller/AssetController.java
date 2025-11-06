package com.devbrovki.sentiment.controller;

import com.devbrovki.sentiment.dto.OptimizationResultDTO;
import com.devbrovki.sentiment.services.ModelStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/assets")
public class AssetController {

    private final ModelStore modelStore;

    public AssetController(ModelStore modelStore) {
        this.modelStore = modelStore;
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
            return ResponseEntity.ok(modelStore.getDecayedScores(asset));
        }else{
            return ResponseEntity.ok(modelStore.getSentiments(asset));
        }
    }

    @GetMapping("/{asset}/params")
    public ResponseEntity<OptimizationResultDTO> getAssetParams(@PathVariable String asset) {
        return ResponseEntity.ok(modelStore.getBestOptimizationResultDTO());
    }
}
