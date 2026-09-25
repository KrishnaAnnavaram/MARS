package com.mars.harness.kernel.core.verdict;

import com.mars.harness.kernel.core.validation.DimensionStatus;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic verdict computation (spec §22). The order of precedence is fixed:
 *
 * <ol>
 *   <li>any hard failure: {@link Verdict.Outcome#BLOCKED}. A failed mandatory dimension, a still
 *       vulnerable remediated finding, a failed applied change, or a mutation bypass. Nothing
 *       overrides this.</li>
 *   <li>any pending human decision: {@link Verdict.Outcome#NEEDS_HUMAN}</li>
 *   <li>any mandatory dimension without evidence: {@link Verdict.Outcome#INSUFFICIENT_EVIDENCE}</li>
 *   <li>any objective left unresolved (platform-blocked, deferred, rejected, not compared):
 *       {@link Verdict.Outcome#PARTIAL}</li>
 *   <li>otherwise {@link Verdict.Outcome#CLEARED}</li>
 * </ol>
 */
public final class VerdictCalculator {

    private static final Set<Verdict.ItemStatus> HARD = EnumSet.of(Verdict.ItemStatus.STILL_VULNERABLE,
            Verdict.ItemStatus.FAILED);
    private static final Set<Verdict.ItemStatus> PENDING = EnumSet.of(Verdict.ItemStatus.PENDING_APPROVAL,
            Verdict.ItemStatus.NEEDS_HUMAN);
    private static final Set<Verdict.ItemStatus> UNRESOLVED = EnumSet.of(Verdict.ItemStatus.BLOCKED_BY_PLATFORM,
            Verdict.ItemStatus.DEFERRED_BY_DEVELOPER, Verdict.ItemStatus.REJECTED_BY_DEVELOPER,
            Verdict.ItemStatus.NOT_COMPARED, Verdict.ItemStatus.INSUFFICIENT_EVIDENCE,
            Verdict.ItemStatus.MIGRATION_INCOMPLETE);

    private VerdictCalculator() {
    }

    public static Verdict compute(String runId, List<Verdict.ItemResult> items, ValidationResult validation,
                                  List<String> pendingDecisions, List<String> extraHardFailures,
                                  List<String> evidenceRefs, String policyVersion) {
        List<String> reasons = new ArrayList<>();
        List<String> hard = new ArrayList<>(extraHardFailures == null ? List.of() : extraHardFailures);
        List<String> unknown = new ArrayList<>();
        List<String> pending = new ArrayList<>(pendingDecisions == null ? List.of() : pendingDecisions);

        if (validation != null) {
            for (ValidationDimension d : validation.mandatoryFailed()) {
                hard.add("Mandatory validation dimension " + d + " FAILED: "
                        + validation.dimension(d).map(ValidationResult.DimensionResult::summary).orElse(""));
            }
            for (ValidationDimension d : validation.mandatoryUnknown()) {
                DimensionStatus s = validation.status(d);
                unknown.add(d + "=" + s);
            }
            validation.dimension(ValidationDimension.MUTATION_BYPASS)
                    .filter(r -> r.status() == DimensionStatus.FAIL)
                    .ifPresent(r -> {
                        if (!hard.contains("Mutation bypass detected: " + r.summary())) {
                            hard.add("Mutation bypass detected: " + r.summary());
                        }
                    });
        }
        for (Verdict.ItemResult item : items) {
            if (HARD.contains(item.status())) {
                hard.add(item.kind() + " " + item.itemId() + " is " + item.status() + ": " + item.reason());
            }
            if (PENDING.contains(item.status())) {
                pending.add(item.kind() + " " + item.itemId() + " awaits a human decision (" + item.status() + ")");
            }
        }

        Verdict.Outcome outcome;
        if (!hard.isEmpty()) {
            outcome = Verdict.Outcome.BLOCKED;
            reasons.add(hard.size() + " hard failure(s); a hard failed gate is never overridden to CLEARED");
        } else if (!pending.isEmpty()) {
            outcome = Verdict.Outcome.NEEDS_HUMAN;
            reasons.add(pending.size() + " decision(s) outstanding; a missing decision is not an approval");
        } else if (!unknown.isEmpty()) {
            outcome = Verdict.Outcome.INSUFFICIENT_EVIDENCE;
            reasons.add("Mandatory dimension(s) without evidence: " + unknown + " (never counted as PASS)");
        } else if (items.stream().anyMatch(i -> UNRESOLVED.contains(i.status()))) {
            outcome = Verdict.Outcome.PARTIAL;
            items.stream().filter(i -> UNRESOLVED.contains(i.status()))
                    .forEach(i -> reasons.add(i.kind() + " " + i.itemId() + " unresolved: " + i.status()
                            + " - " + i.reason()));
        } else {
            outcome = Verdict.Outcome.CLEARED;
            reasons.add("Every in-scope objective resolved and every mandatory dimension passed");
        }
        return new Verdict(runId, outcome, Instant.now().toString(), items, reasons, hard, unknown, pending,
                evidenceRefs, policyVersion);
    }
}
