package com.mars.harness.kernel.core.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation of a decision before it may be recorded.
 *
 * <p>The rules are those the three source systems agreed on, made machine-checkable:
 *
 * <ul>
 *   <li>An empty rationale is not a decision (Bootshift's DecisionStore).</li>
 *   <li>A missing decision is not an approval. A strongly worded request is not an approval
 *       (VRH).</li>
 *   <li>The actor must be a human role. The harness, its agents and any model identity are
 *       refused, so no AI can approve (spec §16).</li>
 *   <li>Every decision is bound to what it decides: an assessment hash, a proposal hash plus the
 *       baseline seal, or a plan hash.</li>
 * </ul>
 */
public final class DecisionValidator {

    private final Set<String> reservedMachineActors;

    public DecisionValidator(Set<String> reservedMachineActors) {
        this.reservedMachineActors = reservedMachineActors.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    }

    public List<String> validate(Decision d) {
        List<String> errors = new ArrayList<>();
        if (d.runId() == null || d.runId().isBlank()) {
            errors.add("run_id is required");
        }
        if (d.type() == null) {
            errors.add("type is required");
            return errors;
        }
        if (blank(d.actor())) {
            errors.add("actor is required: a decision without an actor is not a decision");
        } else if (isMachineActor(d.actor())) {
            errors.add("actor '" + d.actor() + "' is a reserved machine identity; the harness, its agents and "
                    + "models may propose but never decide");
        }
        if (blank(d.role())) {
            errors.add("role is required");
        } else if (isMachineActor(d.role())) {
            errors.add("role '" + d.role() + "' is a machine role and cannot decide");
        }
        if (d.actorAuthentication() != null && !DecisionActor.validAuthentication(d.actorAuthentication())) {
            errors.add("actor_authentication '" + d.actorAuthentication() + "' is not one of LOCALLY_ASSERTED, "
                    + "DEVELOPMENT_ASSERTED or OIDC_AUTHENTICATED:<issuer>");
        }
        if (blank(d.rationale())) {
            errors.add("rationale is required: an empty rationale is not a decision");
        }
        if (blank(d.selected())) {
            errors.add("selected is required");
            return errors;
        }
        switch (d.type()) {
            case EXECUTION_STRATEGY -> {
                requireEnum(errors, d.selected(), Decision.ExecutionStrategy.class, "strategy");
                if (blank(d.assessmentHash())) {
                    errors.add("assessment_hash is required: the decision must name the assessment it was taken against");
                }
            }
            case POST_SECURITY_MIGRATION -> {
                requireEnum(errors, d.selected(), Decision.MigrationChoice.class, "migration choice");
                if (blank(d.assessmentHash())) {
                    errors.add("assessment_hash of the refreshed assessment is required");
                }
            }
            case PROPOSAL_APPROVAL -> {
                requireEnum(errors, d.selected(), Decision.ApprovalVerdict.class, "verdict");
                if (blank(d.proposalId()) || blank(d.proposalHash())) {
                    errors.add("proposal_id and proposal_hash are required: approval is scoped to one exact proposal");
                }
                if (blank(d.baselineSeal())) {
                    errors.add("baseline_seal is required: approval is scoped to the sealed baseline");
                }
            }
            case MIGRATION_PLAN_APPROVAL -> {
                requireEnum(errors, d.selected(), Decision.ApprovalVerdict.class, "verdict");
                if (blank(d.planId()) || blank(d.planHash())) {
                    errors.add("plan_id and plan_hash are required");
                }
            }
            case APPLY_TO_PROJECT -> {
                requireEnum(errors, d.selected(), Decision.ApprovalVerdict.class, "verdict");
                if (blank(d.ledgerHead())) {
                    errors.add("ledger_head is required: apply is scoped to the exact change history");
                }
            }
        }
        return errors;
    }

    public boolean isMachineActor(String actor) {
        String normalized = actor.trim().toLowerCase(Locale.ROOT);
        if (reservedMachineActors.contains(normalized)) {
            return true;
        }
        return reservedMachineActors.stream().anyMatch(r -> normalized.startsWith(r + ":")
                || normalized.startsWith(r + "/") || normalized.endsWith("[bot]"));
    }

    private static <E extends Enum<E>> void requireEnum(List<String> errors, String value, Class<E> type, String what) {
        try {
            Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            errors.add("'" + value + "' is not a valid " + what + "; expected one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
