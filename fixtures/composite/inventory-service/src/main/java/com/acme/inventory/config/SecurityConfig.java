package com.acme.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Predicate;

@Configuration
public class SecurityConfig {

    private static final String API_KEY = "inv-live-7f3a9c2e";

    @Bean
    public Predicate<String> apiKeyCheck() {
        String expected = API_KEY;
        return candidate -> expected.equals(candidate);
    }
}
