package com.bootshift.ports.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional, bounded AI assistance (spec section 29).
 *
 * <p>The whole pipeline must run correctly with AI_ENABLED=false (R12). When enabled, strict-OSS
 * policy admits only local OSS inference runtimes and models whose licenses pass the gate; the
 * runtime license is never taken as proof of the model license.
 *
 * <p>AI may propose. Only deterministic verification may authorize (R11).
 */
public interface AIProvider {

    enum Task {
        CANDIDATE_KNOWLEDGE_EXTRACTION,
        COMPILER_ERROR_CLUSTERING,
        RESIDUAL_PATCH_PROPOSAL,
        CHARACTERIZATION_SCAFFOLDING,
        MIGRATION_EXPLANATION,
        DIFFERENTIAL_EXPLANATION
    }

    record ModelIdentity(String runtime, String runtimeVersion, String modelName, String modelVersion,
                         String modelDigest, String modelLicense, boolean licenseVerified) {
    }

    record Proposal(String proposalId, Task task, String content, String promptHash,
                    String contextHash, String responseHash, ModelIdentity model,
                    Map<String, String> attributes) {
    }

    boolean enabled();

    ModelIdentity identity();

    /** Returns empty when AI is disabled; callers must treat that as normal, not as an error. */
    Optional<Proposal> propose(Task task, String prompt, Map<String, String> context,
                               List<String> priorFailures);
}
