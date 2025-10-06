package com.devbrovki.sentiment;

import com.devbrovki.sentiment.model.SentimentResult;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.devbrovki.sentiment.model.Event;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Consumer {
    private final KafkaTemplate<String, Event> kafkaTemplate;

    @Value("${num.assets}")
    private int numAssets;

    private Map<String, double[]>[][] scores;

    private double[] lambdaDenominators = new double[500];

    private double[][] totalScoresArr;
    private double[][] totalWeightArr;

    private long[][] lastUpdateTSArr;

    @PostConstruct
    public void init() {
        double denom = 1000;
        for (int i = 0; i < lambdaDenominators.length; i++) {
            lambdaDenominators[i] = (denom - (denom % 50));
            denom *= 1.05;
        }

        totalScoresArr = new double[numAssets][lambdaDenominators.length];
        totalWeightArr = new double[numAssets][lambdaDenominators.length];
        lastUpdateTSArr = new long[numAssets][lambdaDenominators.length];
        scores = new Map[numAssets][lambdaDenominators.length];

        for (int i = 0; i < numAssets; i++) {
            for (int j = 0; j < lambdaDenominators.length; j++) {
                scores[i][j] = new ConcurrentHashMap<>();
            }
        }
    }

    @Autowired
    public Consumer(KafkaTemplate<String, Event> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }


    @KafkaListener(groupId = "consumer", topics = "sentiment-gemini")
    public void listenGemini(ConsumerRecord<String, SentimentResult> record) {
        recalculateScores(4, record);
    }

    @KafkaListener(groupId = "consumer", topics = "sentiment-gpt")
    public void listenGPT(ConsumerRecord<String, SentimentResult> record) {
        recalculateScores(5, record);
    }

    @KafkaListener(groupId = "consumer", topics = "sentiment-nlp")
    public void listenNLP(ConsumerRecord<String, SentimentResult> record) {
        recalculateScores(1, record);
    }

    private void recalculateScores(double modelWeight,ConsumerRecord<String, SentimentResult> record) {
        for (int i = 0; i < lambdaDenominators.length; i++) {
            SentimentResult result = record.value();

            int partition = record.partition();

            double totalWeight = totalWeightArr[partition][i];
            double totalScore = totalScoresArr[partition][i];
            long lastUpdateTS = lastUpdateTSArr[partition][i];
            Map<String, double[]> scoresMap = scores[partition][i];

            double[] arr = scoresMap.computeIfAbsent(result.getId(), k ->
                    new double[]{0, 0, Math.max(0.01, result.getScore() * result.getScore()), result.getTimeStamp()});

            double oldScore = arr[0];
            double oldWeight = arr[1];

            double newWeight = oldWeight + modelWeight;

            arr[0] = (oldScore * oldWeight + modelWeight * result.getScore()) / newWeight;
            arr[1] = newWeight;

            double N_weight = arr[2];

            if(lastUpdateTS == 0){
                lastUpdateTS = result.getTimeStamp();
                lastUpdateTSArr[partition][i] = lastUpdateTS;
            }

            double delta = Math.abs(result.getTimeStamp() - lastUpdateTS) / lambdaDenominators[i];
            double decayFactor = Math.round(Math.exp(-delta) * 1000) / 1000.0;

            if(result.getTimeStamp() > lastUpdateTS) {
                totalWeight *= decayFactor;
                totalWeightArr[partition][i] = totalWeight;

                lastUpdateTS = result.getTimeStamp();
                lastUpdateTSArr[partition][i] = lastUpdateTS;
            }else{
                N_weight *= decayFactor;
                arr[2] = N_weight;
            }

            totalScore = Math.round(
                    ((totalScore * totalWeight + N_weight * (arr[0] - oldScore)) / (totalWeight + N_weight))
                            * 1000) / 1000.0;
            totalScoresArr[partition][i] = totalScore;

            totalWeight = Math.round((totalWeight + N_weight) * 1000) / 1000.0;
            totalWeightArr[partition][i] = totalWeight;

            Event event = Event.builder()
                    .score(totalScore)
                    .timeStamp(lastUpdateTS)
                    .build();

            List <Header> headers = new ArrayList<>();
            headers.add(new RecordHeader("lambdaDenominator",
                    ByteBuffer.allocate(8).putDouble(lambdaDenominators[i]).array()));

            ProducerRecord<String, Event> producerRecord =
                    new ProducerRecord <>("chart", partition, result.getId(), event, headers);

            kafkaTemplate.send(producerRecord);
        }
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 86_400_000)
    private void cleanup(){
        for (int i = 0; i < scores.length; i++) {
            for(int j = 0; j < lambdaDenominators.length; j++){
                Map<String, double[]> scoresMap = scores[i][j];

                for (Map.Entry<String, double[]> entry : scoresMap.entrySet()) {
                    long timestamp = (long) entry.getValue()[3];

                    if(lastUpdateTSArr[i][j] >= timestamp + (30L * 86_400_000)){
                        scoresMap.remove(entry.getKey());
                    }
                }
            }
        }
    }
}
