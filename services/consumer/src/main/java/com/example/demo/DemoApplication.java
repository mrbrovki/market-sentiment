package com.example.demo;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public NewTopic topic() {
		return TopicBuilder.name("sentiment")
				.partitions(1)
				.replicas(1)
				.build();
	}
	@Bean

	public NewTopic topic1() {
		return TopicBuilder.name("api-updates")
				.partitions(1)
				.replicas(1)
				.build();
	}

	@KafkaListener(id = "ehhh", topics = "sentiment")
	public void listen(String in) {
		System.out.println("################################################");
		System.out.println(in);
	}
}
