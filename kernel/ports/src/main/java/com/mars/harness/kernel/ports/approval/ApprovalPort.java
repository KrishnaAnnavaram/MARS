package com.mars.harness.kernel.ports.approval;

import com.mars.harness.kernel.core.decision.Decision;

import java.util.List;
import java.util.Optional;

/**
 * Records and reads human decisions (spec §27 {@code ApprovalPort}).
 *
 * <p>Recording is reachable only from the developer-facing entry point (the CLI composition root).
 * Architecture tests forbid capability packs and AI adapters from depending on the recording
 * side. A decision is write-once: a changed mind is a new decision that supersedes the old one,
 * never an edit.
 */
public interface ApprovalPort {

    /** Validates, integrity-hashes and persists a decision. Refuses invalid or machine-actor decisions. */
    Decision record(Decision draft);

    Optional<Decision> find(String decisionId);

    List<Decision> all();

    /** Latest decision of a type, or for a proposal; later decisions supersede earlier ones. */
    Optional<Decision> latest(Decision.DecisionType type);

    Optional<Decision> latestForProposal(String proposalId);

    /** Re-checks every stored integrity hash. Returns the IDs of tampered decisions. */
    List<String> verifyIntegrity();
}
