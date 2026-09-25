package com.mars.harness.tests.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.1 schema validation: the versioned contracts in {@code schemas/v1} are checked against
 * every artifact a real composite run produced (both capabilities, approvals, applied changes,
 * validation and verdict), and they reject the malformed shapes they exist to reject.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaValidationTest {

    @TempDir
    static Path temp;
    private TestHarness h;
    private RunSession session;
    private JsonSchemaFactory factory;

    @BeforeAll
    void run() {
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder -> builder.schemaMappers(mappers ->
                mappers.mapPrefix("https://mars.harness/schemas/", TestHarness.harnessRoot().resolve("schemas").toUri().toString())));
        h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "MIGRATE_FIRST", "dev.lead", "owner", "Recommended order");
        h.engine.resume(run, false);
        h.approve(run, h.proposalFor(run, "INV-101"));
        h.engine.decideProposal(run, h.proposalFor(run, "INV-102").proposalId(), "REJECTED", "dev.lead", "owner", "Later");
        h.engine.resume(run, true);
        session = h.session(run);
    }

    private JsonSchema schema(String name) {
        return factory.getSchema(SchemaLocation.of("https://mars.harness/schemas/v1/" + name + ".schema.json"));
    }

    private static void assertValid(JsonSchema schema, JsonNode node, String what) {
        Set<ValidationMessage> errors = schema.validate(node);
        assertThat(errors).as(what + " -> " + node.toString().substring(0, Math.min(400, node.toString().length()))).isEmpty();
    }

    private static List<Path> files(Path dir, String prefix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<JsonNode> jsonl(Path file, String field) {
        List<JsonNode> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    out.add(KernelJson.parse(line).path(field));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void everyRunArtifactConformsToItsContract() {
        JsonSchema finding = schema("finding");
        JsonNode findings = KernelJson.read(session.layout.findings());
        assertThat(findings.size()).isEqualTo(5);
        findings.forEach(f -> assertValid(finding, f, "finding " + f.path("source_finding_id").asText()));

        JsonSchema proposal = schema("change-proposal");
        List<Path> proposals = files(session.layout.proposals(), "PROP-");
        assertThat(proposals).hasSizeGreaterThan(4);
        proposals.forEach(p -> assertValid(proposal, KernelJson.read(p), p.getFileName().toString()));

        JsonSchema decision = schema("decision");
        List<Path> decisions = files(session.layout.decisions(), "DEC-");
        assertThat(decisions).hasSizeGreaterThanOrEqualTo(3);
        decisions.forEach(d -> assertValid(decision, KernelJson.read(d), d.getFileName().toString()));

        assertValid(schema("migration-assessment"), h.json(session.layout.runId(), "discovery/migration/migration-assessment.json"),
                "migration assessment");
        JsonSchema evidence = schema("evidence-record");
        List<JsonNode> records = jsonl(session.layout.evidenceLog(), "record");
        assertThat(records).hasSizeGreaterThan(20);
        records.forEach(r -> assertValid(evidence, r, "evidence " + r.path("evidence_id").asText()));
        JsonSchema lineage = schema("lineage-entry");
        List<JsonNode> entries = jsonl(session.layout.lineageLedger(), "entry");
        assertThat(entries).isNotEmpty();
        entries.forEach(e -> assertValid(lineage, e, "lineage " + e.path("sequence").asText()));
        assertValid(schema("validation-result"), h.json(session.layout.runId(), "validation/latest.json"), "validation");
        assertValid(schema("verdict"), h.json(session.layout.runId(), "reports/verdict.json"), "verdict");
    }

    @Test
    void theContractsRejectWhatTheyExistToReject() {
        ObjectNode decision = (ObjectNode) KernelJson.read(files(session.layout.decisions(), "DEC-").get(0));
        assertThat(schema("decision").validate(decision.deepCopy().put("rationale", "  "))).isNotEmpty();
        ObjectNode noActor = decision.deepCopy();
        noActor.remove("actor");
        assertThat(schema("decision").validate(noActor)).isNotEmpty();
        assertThat(schema("decision").validate(decision.deepCopy().put("actor_authentication", "SSO_VERIFIED")))
                .as("identity is locally asserted; nothing may claim otherwise").isNotEmpty();

        JsonNode anyProposal = files(session.layout.proposals(), "PROP-").stream().map(KernelJson::read)
                .filter(p -> !p.path("strategy_only").asBoolean()).findFirst().orElseThrow();
        ObjectNode traversal = (ObjectNode) anyProposal.deepCopy();
        ((ObjectNode) traversal.path("edits").get(0)).put("path", "../../etc/passwd");
        assertThat(schema("change-proposal").validate(traversal)).isNotEmpty();
        ObjectNode unanchored = (ObjectNode) anyProposal.deepCopy();
        ((ObjectNode) unanchored.path("edits").get(0)).remove("file_id");
        assertThat(schema("change-proposal").validate(unanchored)).as("a MODIFY without FILE_ID").isNotEmpty();
        ObjectNode noBase = (ObjectNode) anyProposal.deepCopy();
        noBase.remove("baseline_seal");
        assertThat(schema("change-proposal").validate(noBase)).isNotEmpty();

        ObjectNode assessment = (ObjectNode) h.json(session.layout.runId(), "discovery/migration/migration-assessment.json").deepCopy();
        assertThat(assessment.path("traffic_light").asText()).isEqualTo("RED");
        assertThat(schema("migration-assessment").validate(assessment.deepCopy().put("need", "RECOMMENDED")))
                .as("RED always means a prerequisite").isNotEmpty();
        ObjectNode noEvidence = assessment.deepCopy();
        noEvidence.set("evidence", KernelJson.mapper().createArrayNode());
        assertThat(schema("migration-assessment").validate(noEvidence)).as("an assessment must contain evidence").isNotEmpty();

        ObjectNode verdict = (ObjectNode) h.json(session.layout.runId(), "reports/verdict.json").deepCopy();
        verdict.put("outcome", "CLEARED");
        ((ArrayNode) verdict.withArray("hard_failures")).add("a failed gate");
        assertThat(schema("verdict").validate(verdict)).as("a hard failure is never CLEARED").isNotEmpty();
    }
}
