package com.bootshift.tests.core;

import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.evidence.Claim;
import com.bootshift.core.evidence.CoverageStatement;
import com.bootshift.core.evidence.EvidenceLevel;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphDiff;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.policy.HarnessPolicy;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.core.policy.ValidationDepth;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.state.RunState;
import com.bootshift.core.state.StateMachine;
import com.bootshift.core.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the core domain invariants. */
class CoreDomainTest {

    // ------------------------------------------------------------------ canonical JSON

    @Test
    @DisplayName("canonical JSON is key-order independent")
    void canonicalJsonIsStable() {
        String left = Json.canonical(Json.parse("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}"));
        String right = Json.canonical(Json.parse("{\"a\":{\"c\":3,\"d\":2},\"b\":1}"));

        assertThat(left).isEqualTo(right);
        assertThat(Json.canonicalHash(Json.parse("{\"b\":1,\"a\":2}")))
                .isEqualTo(Json.canonicalHash(Json.parse("{\"a\":2,\"b\":1}")));
    }

    // ------------------------------------------------------------------ envelope

    @Test
    @DisplayName("a stage may not shadow a reserved envelope key")
    void reservedKeysAreProtected() {
        Envelope envelope = new Envelope("01-inventory", "1.0.0", "RUN-TEST");
        var payload = Json.obj();
        payload.put("run_id", "SOMETHING-ELSE");

        assertThatThrownBy(() -> com.bootshift.stages.StageSupport.compose(envelope, payload))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("reserved envelope key");
    }

    @Test
    @DisplayName("blind spots and gaps travel with the artifact")
    void envelopeCarriesBlindSpots() {
        Envelope envelope = new Envelope("04-baseline", "1.0.0", "RUN-TEST")
                .blindSpot(new Envelope.BlindSpot("BS-1", "RUNTIME", "module did not start", "no mongo"))
                .gap(new Envelope.Gap("GAP-1", "COVERAGE", "coverage unavailable", "no comparison"));

        var node = envelope.toNode();

        assertThat(node.path("blind_spots")).hasSize(1);
        assertThat(node.path("gaps")).hasSize(1);
        assertThat(node.path("blind_spots").get(0).path("id").asText()).isEqualTo("BS-1");
    }

    // ------------------------------------------------------------------ pointer after write

    @Test
    @DisplayName("latest.json advances only after a successful publish")
    void pointerAdvancesOnlyOnSuccess(@TempDir Path root) {
        OutputLayout layout = new OutputLayout(root);

        assertThat(layout.resolveLatestDir("01-inventory")).isNull();

        OutputLayout.StageWriter first = layout.open("01-inventory");
        first.write("inventory-artifact.json", Map.of("file_count", 10));
        first.publish("RUN-1");
        Path afterFirst = layout.resolveLatestDir("01-inventory");
        assertThat(afterFirst).isNotNull();

        OutputLayout.StageWriter failed = layout.open("01-inventory");
        failed.write("inventory-artifact.json", Map.of("file_count", 99));
        failed.validationError("schema violation");

        assertThatThrownBy(() -> failed.publish("RUN-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to publish");

        // The pointer still names the last good directory, so the next stage reads valid input.
        assertThat(layout.resolveLatestDir("01-inventory")).isEqualTo(afterFirst);
        assertThat(layout.readLatest("01-inventory", "inventory-artifact.json")
                .path("file_count").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("two attempts in the same millisecond get separate directories")
    void attemptsNeverShareADirectory(@TempDir Path root) {
        OutputLayout layout = new OutputLayout(root);

        // The timestamp has millisecond resolution and these two opens are adjacent statements, so
        // they collide. When they shared a directory, a failed attempt overwrote the artifacts of an
        // already-published one in place while the pointer kept naming it - the published directory
        // then held data that had never passed validation.
        OutputLayout.StageWriter first = layout.open("01-inventory");
        OutputLayout.StageWriter second = layout.open("01-inventory");

        assertThat(first.dir()).isNotEqualTo(second.dir());

        first.write("inventory-artifact.json", Map.of("file_count", 10));
        first.publish("RUN-1");
        second.write("inventory-artifact.json", Map.of("file_count", 99));

        // The published attempt is untouched by the unpublished one.
        assertThat(layout.readLatest("01-inventory", "inventory-artifact.json")
                .path("file_count").asInt()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ state machine

    @Test
    @DisplayName("mutating states are refused before the baseline seal")
    void mutationRequiresBaselineSeal() {
        StateMachine machine = new StateMachine();
        machine.restore(RunState.PLAN_FROZEN, false, null);

        assertThatThrownBy(() -> machine.transition(RunState.EDGE_TRANSFORMED, "transform"))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("before the baseline is sealed");
    }

    @Test
    @DisplayName("a sealed baseline cannot be replaced within the same run")
    void baselineSealIsImmutable() {
        StateMachine machine = new StateMachine();
        machine.recordBaselineSeal("hash-one");

        assertThatThrownBy(() -> machine.recordBaselineSeal("hash-two"))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("new run identity");

        // Re-recording the same seal is idempotent, which is what a stage re-run does.
        machine.recordBaselineSeal("hash-one");
        assertThat(machine.baselineSealHash()).isEqualTo("hash-one");
    }

    @Test
    @DisplayName("an undeclared transition is refused")
    void illegalTransitionIsRefused() {
        StateMachine machine = new StateMachine();

        assertThatThrownBy(() -> machine.transition(RunState.MIGRATION_COMPLETE, "skip everything"))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("Illegal state transition");
    }

    @Test
    @DisplayName("re-running a stage is recorded rather than refused")
    void stageRerunIsTolerated() {
        StateMachine machine = new StateMachine();
        machine.transition(RunState.WORKSPACE_READY, "bootstrap");
        machine.transition(RunState.OSS_POLICY_VERIFIED, "gate");
        machine.transition(RunState.INVENTORY_COMPLETE, "inventory");
        machine.transition(RunState.FILE_REGISTRY_SEALED, "seal");
        machine.transition(RunState.BUILD_RESOLVED, "build");

        machine.transition(RunState.INVENTORY_COMPLETE, "re-run inventory");

        assertThat(machine.current()).isEqualTo(RunState.BUILD_RESOLVED);
        assertThat(machine.history()).anyMatch(t -> t.reason().startsWith("RE-RUN"));
    }

    // ------------------------------------------------------------------ policy

    @Test
    @DisplayName("residual coverage escalates validation depth by policy")
    void residualEscalationFollowsPolicy() {
        HarnessPolicy policy = HarnessPolicy.production();

        assertThat(policy.escalateForResidual(ValidationDepth.BUILD_AND_TESTS, 0.95))
                .isEqualTo(ValidationDepth.BUILD_AND_TESTS);
        assertThat(policy.escalateForResidual(ValidationDepth.BUILD_AND_TESTS, 0.75))
                .isEqualTo(ValidationDepth.BUILD_TESTS_RUNTIME);
        assertThat(policy.escalateForResidual(ValidationDepth.BUILD_AND_TESTS, 0.40))
                .isEqualTo(ValidationDepth.FULL_DIFFERENTIAL);
    }

    @Test
    @DisplayName("validation depth takes the maximum of every requirement")
    void depthIsTheMaximum() {
        ValidationDepth depth = ValidationDepth.max(ValidationDepth.BUILD_AND_TESTS,
                ValidationDepth.IMPACTED_DIFFERENTIAL, ValidationDepth.BUILD_ONLY, null);

        assertThat(depth).isEqualTo(ValidationDepth.IMPACTED_DIFFERENTIAL);
        assertThat(depth.requiresTests()).isTrue();
        assertThat(depth.requiresRuntime()).isTrue();
        assertThat(depth.requiresDifferential()).isTrue();
    }

    @Test
    @DisplayName("the policy hash changes when a threshold changes")
    void policyHashIsSensitive() {
        assertThat(HarnessPolicy.production().policyHash())
                .isNotEqualTo(HarnessPolicy.development().policyHash());
    }

    // ------------------------------------------------------------------ license gate

    @Test
    @DisplayName("an unknown license is blocked, not waved through")
    void unknownLicenseIsBlocked() {
        LicensePolicy policy = new LicensePolicy();

        assertThat(policy.evaluate("com.example:thing", "1.0", null, "probe").verdict())
                .isEqualTo(LicensePolicy.Verdict.UNKNOWN);
        assertThat(policy.evaluate("com.example:thing", "1.0", "Some Bespoke Terms", "probe").verdict())
                .isEqualTo(LicensePolicy.Verdict.UNKNOWN);
        assertThat(policy.evaluate("org.apache.commons:commons-lang3", "3.14", "Apache-2.0", "pom")
                .verdict()).isEqualTo(LicensePolicy.Verdict.ALLOWED);
    }

    @Test
    @DisplayName("a source-available recipe estate is blocked by coordinate")
    void forbiddenArtifactIsBlockedByCoordinate() {
        LicensePolicy policy = new LicensePolicy();

        LicensePolicy.Finding finding = policy.evaluate(
                "org.openrewrite.recipe:rewrite-spring", "5.0.0", "Apache-2.0", "pom");

        assertThat(finding.verdict()).isEqualTo(LicensePolicy.Verdict.BLOCKED);
        assertThat(finding.reason()).contains("forbidden list");
    }

    @Test
    @DisplayName("the gate fails when any finding is not ALLOWED")
    void gateFailsOnAnyNonAllowed() {
        LicensePolicy policy = new LicensePolicy();
        List<LicensePolicy.Finding> findings = List.of(
                policy.evaluate("a:b", "1", "Apache-2.0", "pom"),
                policy.evaluate("c:d", "1", null, "pom"));

        assertThat(LicensePolicy.gatePasses(findings)).isFalse();
        assertThat(LicensePolicy.blocking(findings)).hasSize(1);
    }

    // ------------------------------------------------------------------ sensitive data

    @Test
    @DisplayName("credentials embedded in a URI are redacted")
    void credentialsAreRedacted() {
        String uri = "mongodb+srv://appuser:s3cr3tP4ss@cluster0.example.mongodb.net/?retryWrites=true";

        assertThat(SensitiveValues.looksSensitive(uri)).isTrue();
        String redacted = SensitiveValues.redactUri(uri);
        assertThat(redacted).doesNotContain("s3cr3tP4ss");
        // The username goes too. It is half of a credential pair, and keeping it so the URI stayed
        // "inspectable" put the corpus username into a published artifact while the policy said no
        // sensitive value is ever stored.
        assertThat(redacted).doesNotContain("appuser");
        assertThat(redacted).contains("REDACTED:REDACTED@");
        // What a reviewer actually needs - the scheme and the host - survives.
        assertThat(redacted).startsWith("mongodb+srv://");
        assertThat(redacted).contains("cluster0.example.mongodb.net");
    }

    @Test
    @DisplayName("a sensitive value is described by metadata, never by plaintext")
    void sensitiveValuesAreDescribedNotStored() {
        var described = SensitiveValues.describe("spring.datasource.password", "hunter2",
                "PROPERTY_FILE", SensitiveValues.EvidencePolicy.NEVER_STORE_PLAINTEXT, null);

        assertThat(described.path("present").asBoolean()).isTrue();
        assertThat(described.path("value_hash").isNull()).isTrue();
        assertThat(described.toString()).doesNotContain("hunter2");
        assertThat(described.path("value_length").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("a keyed hash is produced only when policy permits it")
    void keyedHashRequiresPermission() {
        var permitted = SensitiveValues.describe("api.key", "abc123", "ENVIRONMENT_VARIABLE",
                SensitiveValues.EvidencePolicy.KEYED_HASH_PERMITTED, "comparison-key");

        assertThat(permitted.path("value_hash").isNull()).isFalse();
        assertThat(permitted.toString()).doesNotContain("abc123");
    }

    @Test
    @DisplayName("secret-looking keys are recognised")
    void sensitiveKeysAreRecognised() {
        assertThat(SensitiveValues.isSensitiveKey("spring.datasource.password")).isTrue();
        assertThat(SensitiveValues.isSensitiveKey("app.api-key")).isTrue();
        assertThat(SensitiveValues.isSensitiveKey("management.client.secret")).isTrue();
        assertThat(SensitiveValues.isSensitiveKey("server.port")).isFalse();
    }

    // ------------------------------------------------------------------ graph

    private ApplicationGraph sampleGraph() {
        ApplicationGraph graph = new ApplicationGraph();
        graph.addNode(new GraphNode("TYPE:Controller", NodeType.CONTROLLER, "Controller")
                .setFqn("com.example.Controller").setFileId("FILE-1"));
        graph.addNode(new GraphNode("TYPE:Service", NodeType.SERVICE, "Service")
                .setFqn("com.example.Service").setFileId("FILE-2"));
        graph.addNode(new GraphNode("TYPE:Repository", NodeType.REPOSITORY, "Repository")
                .setFqn("com.example.Repository").setFileId("FILE-3"));
        graph.addEdge(new GraphEdge("TYPE:Controller", EdgeType.CALLS_SERVICE, "TYPE:Service", "SpringGraph"));
        graph.addEdge(new GraphEdge("TYPE:Service", EdgeType.CALLS_REPOSITORY, "TYPE:Repository",
                "SpringGraph"));
        return graph;
    }

    @Test
    @DisplayName("blast radius reaches transitive dependents and explains each one")
    void blastRadiusExplainsItself() {
        ApplicationGraph graph = sampleGraph();

        List<ApplicationGraph.Reached> reached = graph.blastRadius("TYPE:Repository", 5);

        assertThat(reached).extracting(ApplicationGraph.Reached::nodeId)
                .containsExactlyInAnyOrder("TYPE:Service", "TYPE:Controller");
        assertThat(reached).allSatisfy(hit -> assertThat(hit.path()).isNotEmpty());
        ApplicationGraph.Reached controller = reached.stream()
                .filter(r -> r.nodeId().equals("TYPE:Controller")).findFirst().orElseThrow();
        assertThat(controller.distance()).isEqualTo(2);
        assertThat(controller.path()).hasSize(2);
    }

    @Test
    @DisplayName("the structural hash is order independent but change sensitive")
    void structuralHashIsMeaningful() {
        ApplicationGraph first = sampleGraph();
        ApplicationGraph second = new ApplicationGraph();
        second.addNode(new GraphNode("TYPE:Repository", NodeType.REPOSITORY, "Repository")
                .setFqn("com.example.Repository").setFileId("FILE-3"));
        second.addNode(new GraphNode("TYPE:Service", NodeType.SERVICE, "Service")
                .setFqn("com.example.Service").setFileId("FILE-2"));
        second.addNode(new GraphNode("TYPE:Controller", NodeType.CONTROLLER, "Controller")
                .setFqn("com.example.Controller").setFileId("FILE-1"));
        second.addEdge(new GraphEdge("TYPE:Service", EdgeType.CALLS_REPOSITORY, "TYPE:Repository",
                "SpringGraph"));
        second.addEdge(new GraphEdge("TYPE:Controller", EdgeType.CALLS_SERVICE, "TYPE:Service",
                "SpringGraph"));

        assertThat(second.structuralHash()).isEqualTo(first.structuralHash());

        second.addNode(new GraphNode("TYPE:Extra", NodeType.CLASS, "Extra").setFqn("com.example.Extra"));
        assertThat(second.structuralHash()).isNotEqualTo(first.structuralHash());
    }

    @Test
    @DisplayName("graph diff reports added and removed nodes and edges")
    void graphDiffDetectsChange() {
        ApplicationGraph before = sampleGraph();
        ApplicationGraph after = sampleGraph();
        after.addNode(new GraphNode("TYPE:Added", NodeType.CLASS, "Added")
                .setFqn("com.example.Added").setFileId("FILE-4").setSymbolId("SYM-4"));
        after.addEdge(new GraphEdge("TYPE:Service", EdgeType.USES_TYPE, "TYPE:Added", "SymbolGraph"));

        GraphDiff diff = GraphDiff.between(before, after);

        assertThat(diff.isEmpty()).isFalse();
        assertThat(diff.nodesAdded()).hasSize(1);
        assertThat(diff.edgesAdded()).hasSize(1);
        assertThat(diff.changedFileIds()).contains("FILE-4");
        assertThat(diff.changedSymbolIds()).contains("SYM-4");
    }

    @Test
    @DisplayName("an identical rebuild produces an empty diff")
    void identicalRebuildIsEmpty() {
        assertThat(GraphDiff.between(sampleGraph(), sampleGraph()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("runtime edges are a distinct layer from static edges")
    void runtimeEdgesAreDistinct() {
        assertThat(EdgeType.CALLS_SERVICE.isStatic()).isTrue();
        assertThat(EdgeType.CALLS_SERVICE.isRuntimeObserved()).isFalse();
        assertThat(EdgeType.ACTUALLY_BINDS_PROPERTY.isRuntimeObserved()).isTrue();
        assertThat(EdgeType.ACTUALLY_HANDLES_ENDPOINT.isRuntimeObserved()).isTrue();
    }

    // ------------------------------------------------------------------ evidence

    @Test
    @DisplayName("a claim without coverage or evidence is not publishable")
    void claimsRequireCoverageAndEvidence() {
        Claim bare = new Claim("CL-1", "SECURITY", "Authorization was preserved.")
                .level(EvidenceLevel.E4);
        assertThat(bare.isPublishable()).isFalse();

        Claim withEvidenceOnly = new Claim("CL-2", "SECURITY", "Authorization was preserved.")
                .level(EvidenceLevel.E4).evidence("17-differential/differential-report.json");
        assertThat(withEvidenceOnly.isPublishable()).isFalse();

        Claim complete = new Claim("CL-3", "SECURITY", "Authorization was preserved.")
                .level(EvidenceLevel.E4)
                .evidence("17-differential/differential-report.json")
                .coverage(new CoverageStatement("protected endpoints", 41, 44, 3,
                        List.of("GAP-021"), null));
        assertThat(complete.isPublishable()).isTrue();
        assertThat(complete.render()).contains("41/44 protected endpoints observed")
                .contains("3 unobservable").contains("GAP-021");
    }

    @Test
    @DisplayName("a sealed evidence manifest detects tampering and tolerates archival")
    void evidenceManifestVerifies(@TempDir Path root) throws Exception {
        var store = new com.bootshift.adapters.evidence.FilesystemEvidenceStore(root);
        var stored = store.put("artifact", "a.json", "{\"a\":1}".getBytes(),
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", "test");

        EvidenceManifest manifest = new EvidenceManifest("RUN-TEST");
        manifest.add(new EvidenceManifest.Entry(stored.evidenceId(), "artifact", stored.relativePath(),
                stored.sha256(), stored.sizeBytes(), EvidenceManifest.Classification.INTERNAL,
                "SEALED_EVIDENCE", "test", "now"));
        manifest.claim(new Claim("CL-1", "BUILD", "It compiles.")
                .level(EvidenceLevel.E2).evidence("13/build-report.json")
                .coverage(CoverageStatement.of("modules", 6, 6)));
        manifest.seal("ledger_head", "abc");
        manifest.finalizeManifest();

        Path manifestFile = root.resolve("evidence-manifest.json");
        Json.write(manifestFile, manifest.toNode());

        assertThat(EvidenceManifest.verify(manifestFile, root).valid()).isTrue();

        // Tampering with a stored object is detected.
        Files.writeString(root.resolve(stored.relativePath()), "{\"a\":2}");
        var tampered = EvidenceManifest.verify(manifestFile, root);
        assertThat(tampered.valid()).isFalse();
        assertThat(tampered.entryResults()).anyMatch(r -> "TAMPERED".equals(r.outcome()));

        // Pruning the payload leaves the manifest verifiable, reported as archived.
        Files.delete(root.resolve(stored.relativePath()));
        var archived = EvidenceManifest.verify(manifestFile, root);
        assertThat(archived.entryResults()).anyMatch(r -> "ARCHIVED".equals(r.outcome()));
        assertThat(archived.valid()).isTrue();
    }

    @Test
    @DisplayName("exit codes stay distinct")
    void exitCodesAreDistinct() {
        assertThat(ExitCode.SUCCESS.code()).isZero();
        assertThat(ExitCode.STAGE_FAILURE.code()).isEqualTo(1);
        assertThat(ExitCode.STRUCTURED_REFUSAL.code()).isEqualTo(2);
        assertThat(ExitCode.POLICY_BLOCK.code()).isEqualTo(3);
        assertThat(ExitCode.HUMAN_DECISION_REQUIRED.code()).isEqualTo(4);
        assertThat(ExitCode.fromCode(3)).isEqualTo(ExitCode.POLICY_BLOCK);
    }
}
