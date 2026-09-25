package com.mars.harness.tests.unit.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.1 migration score and traffic-light classification: every light is grounded in
 * observed evidence (platform, lifecycle facts, requested objectives), and complexity, effort and
 * evidence confidence stay separate.
 */
class MigrationAdvisorTest {

    private static JsonNode assess(TestHarness h) {
        String run = h.analyzeEmployee().runId();
        return h.json(run, "discovery/migration/migration-assessment.json");
    }

    @Test
    void greenWhenSupportedBeyondThePolicyHorizonAndNothingRequiresIt(@TempDir Path temp) {
        JsonNode a = assess(TestHarness.over(temp, "migration/employee-demo-sb3", LocalDate.of(2025, 9, 1)));
        assertThat(a.path("traffic_light").asText()).isEqualTo("GREEN");
        assertThat(a.path("need").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(a.path("traffic_light_rationale").asText()).contains("supported until 2026-06-30");
    }

    @Test
    void yellowWhenSupportEndsInsideThePolicyHorizon(@TempDir Path temp) {
        JsonNode a = assess(TestHarness.over(temp, "migration/employee-demo-sb3", LocalDate.of(2026, 2, 1)));
        assertThat(a.path("traffic_light").asText()).isEqualTo("YELLOW");
        assertThat(a.path("traffic_light_rationale").asText()).contains("under the policy horizon");
    }

    @Test
    void yellowWhenOpenSourceSupportHasEnded(@TempDir Path temp) {
        JsonNode a = assess(TestHarness.over(temp, "migration/employee-demo-sb3"));
        assertThat(a.path("traffic_light").asText()).isEqualTo("YELLOW");
        assertThat(a.path("need").asText()).isEqualTo("RECOMMENDED");
        assertThat(a.path("traffic_light_rationale").asText()).contains("support ended 2026-06-30");
        assertThat(a.path("current").path("lifecycle_quality").asText()).isNotEqualTo("UNKNOWN");
    }

    @Test
    void redComesFromARequestedObjectiveNotFromTheCalendar(@TempDir Path temp) {
        // well inside the support window, yet RED: the springdoc fix needs Boot 4
        TestHarness h = TestHarness.over(temp, "composite/inventory-service", LocalDate.of(2025, 9, 1));
        String run = h.analyzeComposite(true).runId();
        JsonNode a = h.json(run, "discovery/migration/migration-assessment.json");
        assertThat(a.path("traffic_light").asText()).isEqualTo("RED");
        assertThat(a.path("need").asText()).isEqualTo("PREREQUISITE");
        assertThat(a.path("recommended_sequence").asText()).isEqualTo("MIGRATE_FIRST");
        assertThat(a.path("objectives").toString()).contains("\"requires_migration\":true");
        // a RED assessment never starts a migration by itself
        assertThat(h.session(run).record.machine.current.name()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");
        assertThat(h.tools.calls).noneMatch(c -> c.directory().startsWith("round-"));
    }

    @Test
    void unknownWhenNoPlatformCanBeObserved(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        Path pom = h.repository.resolve("pom.xml");
        String text = Files.readString(pom);
        text = text.substring(0, text.indexOf("<parent>")) + text.substring(text.indexOf("</parent>") + "</parent>".length());
        Files.writeString(pom, text.replace("<artifactId>spring-boot-starter-web</artifactId>",
                "<artifactId>spring-boot-starter-web</artifactId>\n            <version>${spring.boot.version}</version>"));
        JsonNode a = h.json(h.analyzeComposite(false).runId(), "discovery/migration/migration-assessment.json");
        assertThat(a.path("traffic_light").asText()).isEqualTo("UNKNOWN");
        assertThat(a.path("complexity").asText()).isEqualTo("UNKNOWN");
        assertThat(a.path("unknowns").toString()).contains("Which Spring Boot version");
    }

    @Test
    void effortIsAnAuditableWeightedSumAndStaysSeparateFromComplexityAndConfidence(@TempDir Path temp) {
        JsonNode a = assess(TestHarness.over(temp, "migration/employee-demo-sb3"));
        UnifiedPolicy policy = UnifiedPolicy.load(TestHarness.harnessRoot().resolve("policies/default/unified-policy.json"));
        JsonNode effort = a.path("effort");
        int sum = 0;
        for (JsonNode factor : effort.path("factors")) {
            String name = factor.path("name").asText();
            assertThat(policy.migration().effortWeights()).containsKey(name);
            assertThat(factor.path("weight").asDouble()).isEqualTo(policy.migration().effortWeights().get(name));
            assertThat(factor.path("normalized").asDouble()).isBetween(0.0, 1.0);
            if (factor.path("known").asBoolean()) {
                assertThat(factor.path("evidence_refs").size()).as(name + " has evidence").isPositive();
            }
            sum += factor.path("contribution").asInt();
        }
        assertThat(effort.path("factors").size()).isEqualTo(policy.migration().effortWeights().size());
        assertThat(effort.path("migration_effort_score").asInt()).isBetween(sum - 1, sum + 1).isBetween(0, 100);
        assertThat(effort.path("weights_version").asText()).isEqualTo(policy.policyVersion());
        // three separate answers, not one blended number
        assertThat(a.path("complexity").asText()).isIn("TRIVIAL", "LOW", "MODERATE", "HIGH", "REARCHITECTURE");
        assertThat(a.path("evidence_confidence").asText()).isIn("HIGH", "MEDIUM", "LOW");
        assertThat(a.path("evidence").size()).as("assessment must contain evidence").isPositive();
        assertThat(a.path("issues").size()).isPositive();
    }
}
