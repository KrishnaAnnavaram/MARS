package com.bootshift.ports.approval;

import java.util.List;
import java.util.Optional;

/**
 * Persistent storage for human decisions, reachable without running Agent 18.
 *
 * <p>Validation and approval were mutually dependent: the differential stage needed to know whether
 * an intentional-change decision existed, and the only thing that could tell it was the approval
 * stage, which ran after validation and derived its gates from validation's own output. In practice
 * that meant a legitimately recorded decision could not influence the validation it was recorded
 * for, and the run had to be driven twice for the decision to take effect.
 *
 * <p>Splitting the store from the stage removes the cycle. Decisions are filed into the store from
 * outside - by an operator, by a CI step, by an enterprise approval system through another adapter -
 * and any stage may read them. Agent 18 keeps its real job: discovering which gates exist, checking
 * the decisions on file against them, and reporting what is still outstanding.
 *
 * <p>Nothing in the harness may write a decision on its own behalf. An implementation that allowed
 * that would make every gate self-satisfiable, so {@link #record} takes an actor and a rationale and
 * refuses without both.
 */
public interface DecisionStore {

    /**
     * How the actor on a decision was established.
     *
     * <p>Named honestly. The local implementation computes a keyed hash over the decision fields
     * with a key held on the same machine; that detects accidental or careless modification and does
     * not authenticate anybody. Calling it a signature would claim a property it does not have.
     */
    enum ActorAuthentication {
        /** The actor is whatever the caller typed. Integrity is checkable; identity is asserted. */
        LOCALLY_ASSERTED,
        /** An external identity provider authenticated the actor and vouched for the record. */
        EXTERNAL_IDENTITY_PROVIDER,
        /** An external system cryptographically signed the decision with a key the harness can verify. */
        CRYPTOGRAPHICALLY_SIGNED
    }

    /** One decision as stored, including how far its integrity and identity can be trusted. */
    record StoredDecision(ApprovalPort.Decision decision, ActorAuthentication authentication,
                          String integrityHash, String integrityAlgorithm, String recordedBy,
                          String storedAt, String sourceRef) {
    }

    /** Result of re-checking a stored decision against its recorded integrity value. */
    record IntegrityCheck(String decisionId, boolean intact, String detail) {
    }

    /**
     * Files a decision.
     *
     * @throws IllegalArgumentException when the actor or the rationale is missing; an empty
     *                                  rationale is not a decision
     */
    StoredDecision record(String requestId, ApprovalPort.Gate gate, String scope, String actor,
                          String role, ApprovalPort.Verdict verdict, String rationale,
                          List<String> evidenceRefs, String policyVersion, String sourceRef);

    /** The decision on file for a request, or empty. The latest wins when a request was re-decided. */
    Optional<StoredDecision> forRequest(String requestId);

    /** Every decision on file, oldest first. */
    List<StoredDecision> all();

    /**
     * Decisions whose scope matches a dimension and module, used by validation to discover an
     * intentional-change decision without going through Agent 18.
     */
    List<StoredDecision> matching(ApprovalPort.Gate gate, String scopeFragment);

    /** Re-computes every stored integrity value and reports mismatches. */
    List<IntegrityCheck> verifyIntegrity();

    /** Human-readable description of where decisions are read from, for the evidence record. */
    String describeSources();
}
