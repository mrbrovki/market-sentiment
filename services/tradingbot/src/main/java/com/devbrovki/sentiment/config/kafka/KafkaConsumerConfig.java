package com.devbrovki.sentiment.config.kafka;

import com.devbrovki.sentiment.dto.OptimizationResultDTO;
import com.devbrovki.sentiment.dto.SentimentScore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    public Map<String, Object> baseConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "tradingbot");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        return props;
    }

    // Generic method to create a ConsumerFactory for any type
    public <T> ConsumerFactory<String, T> createConsumerFactory(Class<T> targetType) {
        return new DefaultKafkaConsumerFactory<>(
                baseConfig(),
                new StringDeserializer(),
                new JsonDeserializer<>(targetType)
        );
    }

    // Generic method to create a ListenerContainerFactory for any type
    public <T> KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, T>>
    createListenerContainerFactory(Class<T> targetType) {

        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(createConsumerFactory(targetType));
        return factory;
    }

    //OptimizationResultDTO
    @Bean
    public ConsumerFactory<String, OptimizationResultDTO> modelParamsConsumerFactory() {return createConsumerFactory(OptimizationResultDTO.class);}
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, OptimizationResultDTO>>
    modelParamsListenerContainerFactory() {
        return createListenerContainerFactory(OptimizationResultDTO.class);
    }

    // SENTIMENT SCORE
    @Bean
    public ConsumerFactory<String, SentimentScore> sentimentConsumerFactory() {return createConsumerFactory(SentimentScore.class);}
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, SentimentScore>>
    sentimentListenerContainerFactory() {
        return createListenerContainerFactory(SentimentScore.class);
    }
}
