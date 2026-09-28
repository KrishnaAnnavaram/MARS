package com.mars.harness.tests.controlcenter;

import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.config.ControlCenterProperties;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.query.PipelineProjector;
import com.mars.harness.controlcenter.query.SecretRedactor;
import com.mars.harness.controlcenter.security.MarsRole;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.engine.run.RunRecord;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Projection, redaction and security mapping rules of the Control Center, without a server. */
class ControlCenterUnitTest {

    private static RunRecord walk(RunPhase... phases) {
        RunRecord record = new RunRecord();
        record.createdAt = Instant.now().minusSeconds(60).toString();
        record.machine.recordBaselineSeal("seal");
        for (RunPhase p : phases) {
            record.machine.transition(p, "to " + p);
        }
        return record;
    }

    private static Map<String, String> statuses(RunRecord record, boolean advancing) {
        return PipelineProjector.project(record, new PipelineProjector.Context(advancing, null, record.verdict, Map.of()))
                .stream().collect(Collectors.toMap(RunDtos.PipelineStage::id, RunDtos.PipelineStage::status));
    }

    private static final RunPhase[] TO_GATE_A = {RunPhase.SOURCE_SNAPSHOTTED, RunPhase.INVENTORY_READY,
            RunPhase.IDENTITY_SEALED, RunPhase.GRAPH_READY, RunPhase.BASELINE_SEALED, RunPhase.DISCOVERY_RUNNING,
            RunPhase.DISCOVERY_READY, RunPhase.WAITING_FOR_EXECUTION_DECISION};

    @Test
    void atGateAOnlyAnalysisIsCompleteAndEveryExecutionStageIsStillOpen() {
        Map<String, String> s = statuses(walk(TO_GATE_A), false);
        assertThat(s.get("DISCOVERY")).isEqualTo("COMPLETED");
        assertThat(s.get("GATE_A")).isEqualTo("WAITING");
        assertThat(s.get("MIGRATION_ROUNDS")).isEqualTo("PENDING");
        assertThat(s.get("GATE_B")).isEqualTo("PENDING");
        assertThat(s.get("VERDICT")).isEqualTo("PENDING");
    }

    @Test
    void analysisStagesAreInProgressBetweenTheirCompletionStates() {
        RunRecord created = new RunRecord();
        created.createdAt = Instant.now().toString();
        // CREATED and advancing: the snapshot (ingest) is what runs now, nothing is finished
        Map<String, String> ingest = statuses(created, true);
        assertThat(ingest.get("SNAPSHOT")).isEqualTo("ACTIVE");
        assertThat(ingest.get("INVENTORY")).isEqualTo("PENDING");
        // INVENTORY_READY means the inventory is done: identity is the stage in progress
        RunRecord record = walk(RunPhase.SOURCE_SNAPSHOTTED, RunPhase.INVENTORY_READY);
        record.createdAt = created.createdAt;
        Map<String, String> s = statuses(record, true);
        assertThat(s.get("SNAPSHOT")).isEqualTo("COMPLETED");
        assertThat(s.get("INVENTORY")).isEqualTo("COMPLETED");
        assertThat(s.get("IDENTITY")).isEqualTo("ACTIVE");
        assertThat(s.get("GRAPH")).isEqualTo("PENDING");
        // nothing advancing it: the analysis was interrupted, shown as such, never as running
        assertThat(statuses(record, false).get("IDENTITY")).isEqualTo("IDLE");
        // a failure lands on the stage that was running
        record.machine.transition(RunPhase.FAILED, "identity crashed");
        assertThat(statuses(record, false).get("IDENTITY")).isEqualTo("FAILED");
    }

    @Test
    void aSecurityOnlyPathSkipsMigrationOnlyWhenTheRunsOwnFactsRuleItOut() {
        RunRecord record = walk(TO_GATE_A);
        record.strategy = "SECURITY_ONLY";
        record.migrationDeclined = true;
        record.machine.transition(RunPhase.EXECUTION_PLANNED, "strategy");
        record.machine.transition(RunPhase.SECURITY_FINDINGS_READY, "security");
        record.machine.transition(RunPhase.SECURITY_ANALYSIS_RUNNING, "planning");
        // Gate A2 may still offer the migration: it is pending, not skipped
        Map<String, String> s = statuses(record, true);
        assertThat(s.get("REMEDIATION_PLANNING")).isEqualTo("ACTIVE");
        assertThat(s.get("MIGRATION_ROUNDS")).isEqualTo("PENDING");
        assertThat(s.get("GATE_A2")).isEqualTo("PENDING");
        // not advancing and not waiting: IDLE, never ACTIVE
        assertThat(statuses(record, false).get("REMEDIATION_PLANNING")).isEqualTo("IDLE");
        // once final validation is reached, what never ran is SKIPPED
        record.machine.transition(RunPhase.SECURITY_COMPLETE, "done");
        record.machine.transition(RunPhase.FINAL_VALIDATION, "final");
        Map<String, String> fin = statuses(record, true);
        assertThat(fin.get("MIGRATION_ROUNDS")).isEqualTo("SKIPPED");
        assertThat(fin.get("GATE_B")).isEqualTo("SKIPPED");
        assertThat(fin.get("FINAL_VALIDATION")).isEqualTo("ACTIVE");
    }

    @Test
    void needsHumanStaysInTheStageThatEscalatedAndFailureIsShownWhereItHappened() {
        RunRecord record = walk(TO_GATE_A);
        record.strategy = "MIGRATION_ONLY";
        record.machine.transition(RunPhase.EXECUTION_PLANNED, "strategy");
        record.machine.transition(RunPhase.MIGRATION_PLANNED, "plan");
        record.machine.transition(RunPhase.MIGRATION_RUNNING, "rounds");
        record.machine.transition(RunPhase.NEEDS_HUMAN, "no pack rule matches");
        Map<String, String> s = statuses(record, false);
        assertThat(s.get("MIGRATION_ROUNDS")).isEqualTo("WAITING");
        assertThat(s.get("VERDICT")).isEqualTo("PENDING");

        RunRecord failed = walk(TO_GATE_A);
        failed.strategy = "MIGRATION_ONLY";
        failed.machine.transition(RunPhase.EXECUTION_PLANNED, "strategy");
        failed.machine.transition(RunPhase.MIGRATION_PLANNED, "plan");
        failed.machine.transition(RunPhase.FAILED, "boom");
        assertThat(statuses(failed, false).get("MIGRATION_PLAN")).isEqualTo("FAILED");
    }

    @Test
    void aBlockedMigrationPlanIsShownAsBlocked() {
        RunRecord record = walk(TO_GATE_A);
        record.strategy = "MIGRATION_ONLY";
        record.machine.transition(RunPhase.EXECUTION_PLANNED, "strategy");
        record.machine.transition(RunPhase.MIGRATION_PLANNED, "plan");
        record.machine.transition(RunPhase.FINAL_VALIDATION, "migration not executable");
        var stages = PipelineProjector.project(record, new PipelineProjector.Context(false, "No reference pack", null, Map.of()));
        assertThat(stages.stream().filter(s -> s.id().equals("MIGRATION_PLAN")).findFirst().orElseThrow().status())
                .isEqualTo("BLOCKED");
        RunDtos.StageProgress progress = PipelineProjector.progress(stages, record);
        assertThat(progress.completed()).isLessThanOrEqualTo(progress.applicable());
        assertThat(progress.basis()).contains("not a time estimate");
    }

    @Test
    void stageDurationsComeFromTransitionTimesOnly() {
        RunRecord record = walk(TO_GATE_A);
        var discovery = PipelineProjector.project(record, new PipelineProjector.Context(false, null, null, Map.of())).stream()
                .filter(s -> s.id().equals("DISCOVERY")).findFirst().orElseThrow();
        assertThat(discovery.enteredAt()).isNotNull();
        assertThat(discovery.exitedAt()).isNotNull();
        assertThat(discovery.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(Instant.parse(discovery.exitedAt())).isAfterOrEqualTo(Instant.parse(discovery.enteredAt()));
    }

    @Test
    void credentialsAreMaskedForDisplayOnly() {
        assertThat(SecretRedactor.redact("private static final String API_KEY = \"sk_live_abcdef123456\";"))
                .doesNotContain("sk_live_abcdef123456").contains("API_KEY").contains("redacted");
        assertThat(SecretRedactor.redact("+spring.datasource.password=hunter2hunter2")).doesNotContain("hunter2hunter2");
        assertThat(SecretRedactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----"))
                .doesNotContain("MIIE");
        String ordinary = "String sql = \"SELECT id FROM product WHERE name = ?\";";
        assertThat(SecretRedactor.redact(ordinary)).isEqualTo(ordinary);
    }

    @Test
    void oidcRolesAreMappedAndUnknownClaimsGrantNothing() throws Exception {
        ControlCenterProperties.Auth auth = new ControlCenterProperties.Auth("oidc", Map.of(), "groups",
                Map.of("mars-approvers", "APPROVER"), "preferred_username");
        Method converter = Class.forName("com.mars.harness.controlcenter.security.SecurityConfiguration")
                .getDeclaredMethod("jwtRoles", ControlCenterProperties.Auth.class);
        converter.setAccessible(true);
        @SuppressWarnings("unchecked")
        var convert = (org.springframework.core.convert.converter.Converter<Jwt, ?>) converter.invoke(null, auth);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").issuer("https://idp.example")
                .claim("groups", List.of("mars-approvers", "VIEWER", "domain-admins")).claim("preferred_username", "ada")
                .subject("sub-1").build();
        JwtAuthenticationToken token = (JwtAuthenticationToken) convert.convert(jwt);
        assertThat(token.getName()).isEqualTo("ada");
        assertThat(token.getAuthorities()).extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder(MarsRole.APPROVER.authority(), MarsRole.VIEWER.authority());
        assertThat(MarsRole.parse("domain-admins")).isEmpty();
    }

    @Test
    void serverPathsAreRedactedFromMessages() {
        ControlCenterPaths paths = new ControlCenterPaths(Path.of("/opt/mars").toAbsolutePath(),
                Path.of("/opt/mars/runs").toAbsolutePath(), List.of(Path.of("/srv/repos").toAbsolutePath()));
        String runs = Path.of("/opt/mars/runs").toAbsolutePath().toString();
        String message = "No run RUN-X under " + runs;
        assertThat(paths.redact(message)).isEqualTo("No run RUN-X under <runs>");
        Function<String, String> display = s -> paths.display(Path.of(s).toAbsolutePath());
        assertThat(display.apply("/srv/repos/composite/inventory-service")).isEqualTo("repos/composite/inventory-service");
    }
}
