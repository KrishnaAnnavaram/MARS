package com.bootshift.ports.differential;

import java.util.List;
import java.util.Map;

/**
 * OLD-vs-NEW behavioural comparison (Agent 17).
 *
 * <p>Normalization is explicit, versioned and hashed. Silently ignoring a dynamic field is exactly
 * the failure mode that makes a differential result worthless, so the normalization policy is part
 * of the evidence.
 */
public interface DifferentialPort {

    enum Dimension {
        HTTP_API, SECURITY_AUTHORIZATION, SERIALIZATION, CONFIGURATION_BINDING, PERSISTENCE_STATE,
        QUERY_RESULT, TRANSACTION_EFFECT, CONTEXT_CAPABILITY, EVENT_MESSAGE, EXTERNAL_INTEGRATION,
        BATCH_RESULT, BUSINESS_RULE_OUTCOME
    }

    enum Classification {
        IDENTICAL, EXPECTED, UNEXPECTED, UNEXPLAINED, NOT_COMPARED
    }

    /** A scenario executed identically against both applications. */
    record Scenario(String scenarioId, Dimension dimension, String description,
                    Map<String, String> inputs, String contractRef) {
    }

    record Observation(String scenarioId, String side, Map<String, Object> raw, String rawHash) {
    }

    /** One normalization rule, e.g. drop a timestamp header. Never applied silently. */
    record NormalizationRule(String ruleId, String dimension, String jsonPointer, String action,
                             String rationale) {
    }

    record NormalizationPolicy(String version, String policyHash, List<NormalizationRule> rules) {
    }

    record Difference(String path, String oldValue, String newValue, String detail) {
    }

    record ComparisonResult(String scenarioId, Dimension dimension, Classification classification,
                            List<Difference> differences, List<String> explanationRefs,
                            String approvalRef, String normalizationPolicyHash, String detail) {
    }

    NormalizationPolicy normalizationPolicy();

    Observation normalize(Observation raw, NormalizationPolicy policy);

    ComparisonResult compare(Scenario scenario, Observation oldSide, Observation newSide,
                             NormalizationPolicy policy, List<String> verifiedFactRefs,
                             List<String> approvals);
}
