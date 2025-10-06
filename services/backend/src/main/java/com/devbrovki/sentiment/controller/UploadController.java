package com.devbrovki.sentiment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.devbrovki.sentiment.model.Result;
import com.devbrovki.sentiment.model.SentimentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;

@RestController
@RequestMapping("/")
public class UploadController {
    private final KafkaTemplate<String, Result> kafkaTemplate;
    private final KafkaTemplate<String, SentimentResult> sentimentKafkaTemplate;

    @Autowired
    public UploadController(KafkaTemplate<String, Result> kafkaTemplate, KafkaTemplate<String, SentimentResult> sentimentKafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.sentimentKafkaTemplate = sentimentKafkaTemplate;
    }

/*
    @PostMapping("upload")
    public void upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
        Result result = new Result();
        while (reader.ready()) {
            String[] parts = reader.readLine().split(",");
            result.setId(Long.parseLong(parts[0]));
            result.setTimeStamp(Long.parseLong(parts[1]));
            result.setTitle(parts[2]);
            kafkaTemplate.send("api-updates", result);
        }
    }


 */
    @PostMapping("upload")
    public void upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return;
        }

        if(file.getContentType().equals("text/csv")){
            for (int i = 0; i < 256; i++) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
                SentimentResult result = new SentimentResult();
                while (reader.ready()) {
                    String[] parts = reader.readLine().split(",");
                    result.setId(Long.parseLong(parts[0]));
                    result.setTimeStamp(Long.parseLong(parts[1]));
                    result.setScore(Double.parseDouble(parts[2]));
                    sentimentKafkaTemplate.send("sentiment", 1, "key", result);
                }
            }
        }else{
            ObjectMapper mapper = new ObjectMapper();
            Result[] results = mapper.readValue(file.getInputStream(), Result[].class);
            for (int i = 0; i < results.length; i++) {
                Result result = results[i];
                result.setId(i);
                kafkaTemplate.send("api-updates", result);
            }

        }
    }
}
