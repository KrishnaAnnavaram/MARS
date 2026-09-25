package com.bootshift.tests.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.util.Json;
import com.bootshift.core.util.SchemaValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema conformance tests (spec section 43).
 *
 * <p>Two things are checked. First, that every schema in the repository is itself a loadable JSON
 * Schema — a broken schema silently disables validation for its stage, which is worse than having no
 * schema at all. Second, that the artifacts a real run produced actually validate.
 *
 * <p>The second half is skipped when no run output is present, so the test is meaningful in CI
 * without requiring a full migration first.
 */
class SchemaConformanceTest {

    private static final Path HARNESS_ROOT = Path.of(System.getProperty("bootshift.harness.root", "."))
            .toAbsolutePath().normalize();

    private static Path schemaRoot() {
        Path direct = HARNESS_ROOT.resolve("schemas");
        return Files.isDirectory(direct) ? direct : HARNESS_ROOT.getParent().resolve("schemas");
    }

    private static Path outputRoot() {
        Path direct = HARNESS_ROOT.resolve("output");
        return Files.isDirectory(direct) ? direct : HARNESS_ROOT.getParent().resolve("output");
    }

    @Test
    @DisplayName("every schema file is a loadable JSON Schema")
    void everySchemaLoads() {
        SchemaValidator validator = new SchemaValidator(schemaRoot());
        assertThat(validator.available())
                .as("schema root %s must exist", schemaRoot())
                .isTrue();

        List<Path> schemas = validator.allSchemas();
        assertThat(schemas).isNotEmpty();

        List<String> broken = new ArrayList<>();
        for (Path schema : schemas) {
            String relative = schemaRoot().relativize(schema).toString().replace('\\', '/');
            // An empty object validates against any well-formed schema or produces normal validation
            // messages. It cannot produce a load failure unless the schema itself is malformed.
            List<String> messages = validator.validate(relative, Json.obj());
            if (messages.stream().anyMatch(m -> m.startsWith("Schema not found"))) {
                broken.add(relative + ": not resolvable");
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    @DisplayName("every schema declares a title and a description")
    void schemasAreDocumented() {
        SchemaValidator validator = new SchemaValidator(schemaRoot());
        List<String> undocumented = new ArrayList<>();

        for (Path schema : validator.allSchemas()) {
            JsonNode node = Json.read(schema);
            if (node.path("title").asText("").isBlank()
                    || node.path("description").asText("").isBlank()) {
                undocumented.add(schema.getFileName().toString());
            }
        }
        assertThat(undocumented).isEmpty();
    }

    @Test
    @DisplayName("the envelope schema covers every reserved key")
    void envelopeSchemaCoversReservedKeys() {
        JsonNode envelope = Json.read(schemaRoot().resolve("common/artifact-envelope.schema.json"));
        JsonNode properties = envelope.path("properties");

        for (String reserved : com.bootshift.core.domain.Envelope.RESERVED_KEYS) {
            assertThat(properties.has(reserved))
                    .as("envelope schema must describe the reserved key %s", reserved)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("artifacts produced by a real run validate against their schemas")
    void publishedArtifactsValidate() {
        Assumptions.assumeTrue(Files.isDirectory(outputRoot()),
                "no run output present; run the pipeline to exercise this test");

        SchemaValidator validator = new SchemaValidator(schemaRoot());
        Map<String, String> artifactToSchema = new LinkedHashMap<>();
        artifactToSchema.put("01-inventory/inventory-artifact.json",
                "inventory/inventory-artifact.schema.json");
        artifactToSchema.put("01-inventory/file-registry.json",
                "file-registry/file-registry.schema.json");
        artifactToSchema.put("02-build/build-model.json", "build/build-model.schema.json");
        artifactToSchema.put("03-graph/application-graph.json", "graph/application-graph.schema.json");
        artifactToSchema.put("03-graph/symbol-registry.json",
                "symbol-registry/symbol-registry.schema.json");
        artifactToSchema.put("04-baseline/baseline-manifest.json",
                "baseline/baseline-manifest.schema.json");
        artifactToSchema.put("05-compatibility/lifecycle-registry.json",
                "compatibility/lifecycle-registry.schema.json");
        artifactToSchema.put("06-target/target-state.json", "target/target-state.schema.json");
        artifactToSchema.put("08-knowledge/migration-knowledge.json",
                "migration-knowledge/migration-knowledge.schema.json");
        artifactToSchema.put("09-impact/impact-report.json", "impact/impact-report.schema.json");
        artifactToSchema.put("10-characterization/characterization-report.json",
                "characterization/characterization-report.schema.json");
        artifactToSchema.put("11-plan/migration-plan.json",
                "migration-plan/migration-plan.schema.json");
        artifactToSchema.put("11-plan/edge-plan.json", "migration-plan/edge-plan.schema.json");
        artifactToSchema.put("14-graph-diff/graph-diff.json", "graph/graph-diff.schema.json");
        artifactToSchema.put("15-test/test-report.json", "validation/test-report.schema.json");
        artifactToSchema.put("16-runtime/runtime-report.json", "validation/runtime-report.schema.json");
        artifactToSchema.put("17-differential/differential-report.json",
                "differential/differential-report.schema.json");
        artifactToSchema.put("18-approval/approval-report.json", "evidence/approval-report.schema.json");
        artifactToSchema.put("19-evidence/migration-result.json",
                "evidence/migration-result.schema.json");

        com.bootshift.core.domain.OutputLayout layout =
                new com.bootshift.core.domain.OutputLayout(outputRoot());

        List<String> failures = new ArrayList<>();
        int validated = 0;
        for (Map.Entry<String, String> entry : artifactToSchema.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            JsonNode artifact = layout.readLatest(parts[0], parts[1]);
            if (artifact == null) {
                continue;
            }
            validated++;
            List<String> messages = validator.validate(entry.getValue(), artifact);
            messages.stream()
                    .filter(m -> !m.startsWith("Schema not found"))
                    .forEach(m -> failures.add(entry.getKey() + ": " + m));
        }

        Assumptions.assumeTrue(validated > 0, "no published artifacts to validate yet");
        assertThat(failures)
                .as("%d published artifact(s) checked", validated)
                .isEmpty();
    }
}
