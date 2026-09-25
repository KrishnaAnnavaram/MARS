package com.example.migrationdemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MIGRATION-DEMO:
 * This Jackson 2 configuration is intentionally included so that
 * Jackson-related changes can be demonstrated during the Spring Boot 4
 * migration.
 *
 * Jackson 2.x is part of Spring Boot 3.x.
 * Spring Boot 4 will include Jackson 3.x, which has breaking API changes.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Support LocalDateTime serialization
        mapper.registerModule(new JavaTimeModule());

        // Use ISO 8601 date/time formatting
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Do not serialize null values
        mapper.setDefaultPropertyInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

        // Disable pretty printing for production
        mapper.disable(SerializationFeature.INDENT_OUTPUT);

        return mapper;
    }

}
