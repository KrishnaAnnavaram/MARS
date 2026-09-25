package com.mars.harness.kernel.core.decision;

import java.util.List;

/**
 * A human decision as a first-class, immutable machine artifact (spec §12, §16, ADR-U006).
 *
 * <p>One shape for every gate. Type-specific fields are null where they do not apply:
 *
 * <ul>
 *   <li>{@link DecisionType#EXECUTION_STRATEGY} (Gate A): {@code selected} is an
 *       {@link ExecutionStrategy}, bound to {@code assessmentHash}</li>
 *   <li>{@link DecisionType#POST_SECURITY_MIGRATION} (Gate A2): {@code selected} is PROCEED, SKIP
 *       or STOP, bound to the refreshed {@code assessmentHash}</li>
 *   <li>{@link DecisionType#PROPOSAL_APPROVAL} (Gate B, plus manual patches): {@code selected} is
 *       APPROVED, REJECTED or DEFERRED, bound to {@code proposalId}, {@code proposalHash} and
 *       {@code baselineSeal}</li>
 *   <li>{@link DecisionType#MIGRATION_PLAN_APPROVAL}: bound to {@code planId} and {@code planHash}</li>
 *   <li>{@link DecisionType#APPLY_TO_PROJECT}: bound to the final ledger head</li>
 * </ul>
 *
 * <p>{@code integrityHash} is a keyed hash that detects modification. It is <b>not</b> a signature
 * and does not authenticate the actor. {@code actorAuthentication} says how the actor was
 * established, following Bootshift's honest naming.
 */
public record Decision(String decisionId, String runId, DecisionType type, String selected, String recommendation,
                       String proposalId, String proposalHash, List<String> findingIds,
                       List<String> affectedFileIds, List<String> affectedSymbolIds,
                       List<String> affectedStatementIds, String planId, String planHash, String assessmentHash,
                       String baselineSeal, String ledgerHead, String actor, String role,
                       String actorAuthentication, String rationale, String timestamp, String policyVersion,
                       String integrityHash) {

    public Decision {
        findingIds = findingIds == null ? List.of() : List.copyOf(findingIds);
        affectedFileIds = affectedFileIds == null ? List.of() : List.copyOf(affectedFileIds);
        affectedSymbolIds = affectedSymbolIds == null ? List.of() : List.copyOf(affectedSymbolIds);
        affectedStatementIds = affectedStatementIds == null ? List.of() : List.copyOf(affectedStatementIds);
    }

    public Decision withIntegrity(String hash) {
        return new Decision(decisionId, runId, type, selected, recommendation, proposalId, proposalHash, findingIds,
                affectedFileIds, affectedSymbolIds, affectedStatementIds, planId, planHash, assessmentHash,
                baselineSeal, ledgerHead, actor, role, actorAuthentication, rationale, timestamp, policyVersion,
                hash);
    }

    public boolean approved() {
        return ApprovalVerdict.APPROVED.name().equals(selected);
    }

    public enum DecisionType {
        EXECUTION_STRATEGY,
        POST_SECURITY_MIGRATION,
        PROPOSAL_APPROVAL,
        MIGRATION_PLAN_APPROVAL,
        APPLY_TO_PROJECT
    }

    public enum ExecutionStrategy {
        MIGRATE_FIRST, SECURITY_FIRST, MIGRATION_ONLY, SECURITY_ONLY, ANALYZE_ONLY, STOP;

        public boolean includesMigration() {
            return this == MIGRATE_FIRST || this == SECURITY_FIRST || this == MIGRATION_ONLY;
        }

        public boolean includesSecurity() {
            return this == MIGRATE_FIRST || this == SECURITY_FIRST || this == SECURITY_ONLY;
        }
    }

    public enum ApprovalVerdict { APPROVED, REJECTED, DEFERRED }

    public enum MigrationChoice { PROCEED, SKIP, STOP }
}
