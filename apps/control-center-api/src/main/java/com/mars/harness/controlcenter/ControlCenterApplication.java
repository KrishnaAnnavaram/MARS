package com.mars.harness.controlcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The MARS Control Center: a second composition root over the unchanged engine, next to the CLI.
 *
 * <pre>
 *   CLI ─────────┐
 *                ▼
 *            MARS engine  (HarnessEngine, RunAdvancer, ApprovalPort, MutationGateway, …)
 *                ▲
 *   Web API ─────┘
 * </pre>
 *
 * <p>It observes runs through their persisted artifacts and execution events, and it acts only
 * through the engine's public operations: {@code analyze}, {@code decide*} and {@code resume}.
 * It never writes run state, decisions or source itself.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ControlCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlCenterApplication.class, args);
    }
}
