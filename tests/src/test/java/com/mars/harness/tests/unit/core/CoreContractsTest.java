package com.mars.harness.tests.unit.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.decision.DecisionValidator;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.core.validation.DimensionStatus;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.core.verdict.VerdictCalculator;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §32.1: IDs, decision validation, authorization rules of the state machine, verdict, policy. */
class CoreContractsTest {

    // ------------------------------------------------------------------ IDs

    @Test
    void idsCarryTheirKindAndAreUniqueAndOrdered() {
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 5000; i++) {
            String id = HarnessIds.allocate(HarnessIds.Kind.STATEMENT);
            assertThat(id).startsWith("STMT-");
            assertThat(seen.add(id)).isTrue();
            if (previous != null) {
                // the 10-character time prefix never goes backwards (the random suffix is not monotonic within a millisecond)
                assertThat(id.substring(5, 15).compareTo(previous.substring(5, 15))).isGreaterThanOrEqualTo(0);
            }
            previous = id;
        }
        assertThatThrownBy(() -> HarnessIds.allocate(HarnessIds.Kind.CHANGE)).as("CHANGE_ID belongs to the ledger")
                .isInstanceOf(IllegalArgumentException.class);
        for (HarnessIds.Kind kind : HarnessIds.Kind.values()) {
            if (kind == HarnessIds.Kind.CHANGE) {
                continue;
            }
            String id = HarnessIds.allocate(kind);
            assertThat(HarnessIds.kindOf(id)).isEqualTo(kind);
            assertThat(HarnessIds.isKind(id, kind)).isTrue();
        }
        assertThat(HarnessIds.kindOf("not-an-id")).isNull();
        // FILE_ID is Bootshift's: the kernel recognises its prefix but does not re-mint it
        assertThat(HarnessIds.kindOf("FILE-01M3B4QPV4DN3X64C43HV7CHJQ")).isEqualTo(HarnessIds.Kind.FILE);
    }

    // ------------------------------------------------------------------ decisions

    private static final DecisionValidator VALIDATOR = new DecisionValidator(Set.of("harness", "llm", "agent", "copilot",
            "claude", "gpt", "bot", "ci"));

    private static Decision approval(String actor, String role, String rationale, String proposalHash, String seal) {
        return new Decision(null, "RUN-1", Decision.DecisionType.PROPOSAL_APPROVAL, "APPROVED", null, "PROP-1", proposalHash,
                List.of(), List.of(), List.of(), List.of(), null, null, null, seal, null, actor, role, null, rationale, null,
                null, null);
    }

    @Test
    void aValidHumanApprovalPasses() {
        assertThat(VALIDATOR.validate(approval("dev.lead", "owner", "Reviewed", "h", "s"))).isEmpty();
    }

    @Test
    void machineActorsAndRolesAreRefusedIncludingQualifiedForms() {
        for (String actor : List.of("llm", "LLM", "claude", "agent:fixer", "copilot", "ci")) {
            assertThat(VALIDATOR.validate(approval(actor, "owner", "Reviewed", "h", "s"))).as(actor)
                    .anyMatch(e -> e.contains("reserved machine identity"));
        }
        assertThat(VALIDATOR.validate(approval("dev.lead", "llm", "Reviewed", "h", "s")))
                .anyMatch(e -> e.contains("machine role"));
    }

    @Test
    void anApprovalMustBeScopedAndJustified() {
        assertThat(VALIDATOR.validate(approval("dev.lead", "owner", " ", "h", "s"))).anyMatch(e -> e.contains("rationale"));
        assertThat(VALIDATOR.validate(approval("dev.lead", "owner", "ok", null, "s"))).anyMatch(e -> e.contains("proposal_hash"));
        assertThat(VALIDATOR.validate(approval("dev.lead", "owner", "ok", "h", null))).anyMatch(e -> e.contains("baseline_seal"));
        assertThat(VALIDATOR.validate(approval(null, "owner", "ok", "h", "s"))).anyMatch(e -> e.contains("actor is required"));
        Decision strategy = new Decision(null, "RUN-1", Decision.DecisionType.EXECUTION_STRATEGY, "YOLO", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null, "s", null, "dev.lead", "owner", null, "because",
                null, null, null);
        assertThat(VALIDATOR.validate(strategy)).anyMatch(e -> e.contains("strategy")).anyMatch(e -> e.contains("assessment_hash"));
    }

    // ------------------------------------------------------------------ state machine

    @Test
    void theStateMachineRefusesSkippedGatesAndUnsealedMutation() {
        RunStateMachine m = new RunStateMachine();
        for (RunPhase p : List.of(RunPhase.SOURCE_SNAPSHOTTED, RunPhase.INVENTORY_READY, RunPhase.IDENTITY_SEALED,
                RunPhase.GRAPH_READY)) {
            m.transition(p, "test");
        }
        assertThatThrownBy(() -> m.transition(RunPhase.MIGRATION_RUNNING, "skip ahead"))
                .isInstanceOf(HarnessOutcomeException.class).hasMessageContaining("Illegal run transition");
        RunStateMachine unsealed = new RunStateMachine();
        unsealed.current = RunPhase.MIGRATION_PLANNED;
        assertThatThrownBy(() -> unsealed.transition(RunPhase.MIGRATION_RUNNING, "no seal"))
                .isInstanceOf(HarnessOutcomeException.class)
                .satisfies(e -> assertThat(((HarnessOutcomeException) e).category()).isEqualTo(OutcomeCategory.BASELINE_INVALID));
        RunStateMachine sealed = new RunStateMachine();
        sealed.recordBaselineSeal("abc");
        assertThatThrownBy(() -> sealed.recordBaselineSeal("def")).isInstanceOf(HarnessOutcomeException.class);
        // no path from the execution gate to any mutating state except through a recorded decision
        assertThat(RunStateMachine.successorsOf(RunPhase.WAITING_FOR_EXECUTION_DECISION))
                .containsExactlyInAnyOrder(RunPhase.EXECUTION_PLANNED, RunPhase.COMPLETE, RunPhase.FAILED);
    }

    // ------------------------------------------------------------------ verdict

    private static Verdict.ItemResult item(String id, Verdict.ItemStatus status) {
        return new Verdict.ItemResult(id, "finding", status, null, "r", List.of());
    }

    private static ValidationResult validation(DimensionStatus compile) {
        return new ValidationResult("VAL-1", "RUN-1", "final", "now", List.of(new ValidationResult.DimensionResult(
                ValidationDimension.COMPILE, compile, null, "s", List.of(), "t")), List.of("COMPILE"));
    }

    @Test
    void verdictPrecedenceIsBlockedNeedsHumanInsufficientPartialCleared() {
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.FIXED)), validation(DimensionStatus.PASS),
                List.of(), List.of(), List.of(), "p").outcome()).isEqualTo(Verdict.Outcome.CLEARED);
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.BLOCKED_BY_PLATFORM)),
                validation(DimensionStatus.PASS), List.of(), List.of(), List.of(), "p").outcome()).isEqualTo(Verdict.Outcome.PARTIAL);
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.FIXED)),
                validation(DimensionStatus.NOT_RUN), List.of(), List.of(), List.of(), "p").outcome())
                .isEqualTo(Verdict.Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.PENDING_APPROVAL)),
                validation(DimensionStatus.NOT_RUN), List.of(), List.of(), List.of(), "p").outcome())
                .isEqualTo(Verdict.Outcome.NEEDS_HUMAN);
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.PENDING_APPROVAL),
                        item("b", Verdict.ItemStatus.STILL_VULNERABLE)), validation(DimensionStatus.PASS), List.of(), List.of(),
                List.of(), "p").outcome()).isEqualTo(Verdict.Outcome.BLOCKED);
        assertThat(VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.FIXED)), validation(DimensionStatus.FAIL),
                List.of(), List.of(), List.of(), "p").outcome()).isEqualTo(Verdict.Outcome.BLOCKED);
    }

    @Test
    void anUnexecutedMandatoryDimensionIsNeverAPass() {
        for (DimensionStatus unknown : List.of(DimensionStatus.NOT_RUN, DimensionStatus.NOT_COMPARED,
                DimensionStatus.TOOL_UNAVAILABLE, DimensionStatus.INSUFFICIENT_EVIDENCE)) {
            assertThat(unknown.passed()).isFalse();
            Verdict v = VerdictCalculator.compute("R", List.of(item("a", Verdict.ItemStatus.FIXED)), validation(unknown),
                    List.of(), List.of(), List.of(), "p");
            assertThat(v.outcome()).as(unknown.name()).isNotEqualTo(Verdict.Outcome.CLEARED);
        }
        // a dimension that is simply absent counts as NOT_RUN
        ValidationResult missing = new ValidationResult("VAL-1", "RUN-1", "final", "now", List.of(), List.of("TESTS"));
        assertThat(missing.status(ValidationDimension.TESTS)).isEqualTo(DimensionStatus.NOT_RUN);
    }

    // ------------------------------------------------------------------ policy

    @Test
    void thePolicyLoadsAndItsEffortWeightsSumTo100() {
        UnifiedPolicy policy = UnifiedPolicy.load(TestHarness.harnessRoot().resolve("policies/default/unified-policy.json"));
        assertThat(policy.migration().effortWeights().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100.0);
        assertThat(policy.reservedActors()).contains("llm", "harness", "agent");
    }

    @Test
    void aPolicyWhoseWeightsDoNotSumTo100IsRefused(@TempDir Path temp) throws Exception {
        Path source = TestHarness.harnessRoot().resolve("policies/default/unified-policy.json");
        ObjectNode node = (ObjectNode) KernelJson.read(source);
        ObjectNode weights = (ObjectNode) node.path("migration").path("effort_weights");
        List<String> names = new ArrayList<>();
        weights.fieldNames().forEachRemaining(names::add);
        weights.put(names.get(0), weights.path(names.get(0)).asDouble() + 7);
        Path bad = temp.resolve("bad-policy.json");
        Files.writeString(bad, KernelJson.mapper().writeValueAsString(node));
        assertThatThrownBy(() -> UnifiedPolicy.load(bad)).hasMessageContaining("100");
    }
}
