package com.haylee.textloop;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TextloopApplication {

	public static void main(String[] args) {
		SpringApplication.run(TextloopApplication.class, args);
	}

	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}

}
