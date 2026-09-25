package com.bootshift.ports.approval;

import java.util.List;
import java.util.Optional;

/**
 * Human decisions the machine must not self-authorize (Agent 18).
 *
 * <p>An empty rationale is not a decision. The port refuses to record one.
 */
public interface ApprovalPort {

    enum Gate {
        INTENTIONAL_SECURITY_CHANGE, PERSISTENCE_SCHEMA_CHANGE, BUSINESS_OUTCOME_CHANGE,
        UNSUPPORTED_INTERNAL_STARTER, BROAD_RESIDUAL_PATCH, HIGH_RISK_AI_PATCH,
        DOCUMENTATION_CONFLICT, SHORT_HORIZON_TARGET, NORMALIZATION_POLICY_CHANGE,
        TEST_EXPECTATION_CHANGE, EVIDENCE_SHORTFALL, CHECKPOINT_COLLAPSE, COVERAGE_REGRESSION
    }

    enum Verdict {
        APPROVED, REJECTED, DEFERRED
    }

    record Request(String requestId, Gate gate, String scope, String summary,
                   List<String> evidenceRefs, String raisedBy, String raisedAt) {
    }

    /**
     * One recorded decision.
     *
     * <p>{@code integrityHash} was called {@code signature}. It is a keyed hash over the decision's
     * fields, computed with a key held alongside the store, so it detects modification and does not
     * authenticate anybody. Naming it a signature claimed a property the harness cannot provide, and
     * a reader who trusted that name would have believed an approval was cryptographically attested
     * when the actor was simply whatever string the caller typed. The
     * {@link DecisionStore.ActorAuthentication} recorded with each stored decision says which of the
     * two it actually is.
     */
    record Decision(String decisionId, String requestId, Gate gate, String actor, String role,
                    String scope, Verdict verdict, String rationale, List<String> evidenceRefs,
                    String policyVersion, String timestamp, String integrityHash) {
    }

    Request raise(Gate gate, String scope, String summary, List<String> evidenceRefs, String raisedBy);

    Decision record(String requestId, String actor, String role, Verdict verdict, String rationale,
                    List<String> evidenceRefs, String policyVersion);

    Optional<Decision> decisionFor(String requestId);

    List<Request> pending();

    List<Decision> decisions();
}
