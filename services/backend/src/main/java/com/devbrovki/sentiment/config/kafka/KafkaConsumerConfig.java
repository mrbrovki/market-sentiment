package com.devbrovki.sentiment.config.kafka;

import com.devbrovki.sentiment.model.Event;
import com.devbrovki.sentiment.model.SentimentResult;
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

    public Map<String, Object> config() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "backend");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, Event.class.getName());
        return props;
    }
/*
    @Bean
    public ConsumerFactory<String, Map<Long, double[]>> consumerFactory() {
        MapDeserializer deserializer = new MapDeserializer();
        return new DefaultKafkaConsumerFactory<>(config(), new StringDeserializer(), deserializer);
    }

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, Map<Long, double[]>>> kafkaListenerContainerFactory(
            ConsumerFactory<String, Map<Long, double[]>> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Map<Long, double[]>> listenerContainerFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        listenerContainerFactory.setConsumerFactory(consumerFactory);
        return listenerContainerFactory;
    }

 */
    @Bean
    public ConsumerFactory<String, Event> eventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(config(), new StringDeserializer(), new JsonDeserializer<>(Event.class));
    }

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, Event>> eventListenerContainerFactory(
            ConsumerFactory<String, Event> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Event> listenerContainerFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        listenerContainerFactory.setConsumerFactory(consumerFactory);
        return listenerContainerFactory;
    }
}
