package com.devbrovki.sentiment;

import com.devbrovki.sentiment.model.Event;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@SpringBootApplication
@EnableKafka
@EnableScheduling

public class BackendApplication {

	public static final Object lock = new Object();
	public static Map<String, List<Event>>[] events;


	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}
}
