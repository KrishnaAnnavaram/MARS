package com.aura.vihanga.shedulerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShedulerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShedulerServiceApplication.class, args);
	}

}
