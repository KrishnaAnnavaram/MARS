package com.mars.harness.kernel.core.validation;

/** The common validation plane (spec §21). Capability-specific checks plug into these dimensions. */
public enum ValidationDimension {
    COMPILE,
    TESTS,
    BUILD_PACKAGE,
    SECURITY_RESCAN,
    DEPENDENCY_SCAN,
    RUNTIME_STARTUP,
    BEHAVIOR_PROBES,
    OLD_NEW_DIFFERENTIAL,
    GRAPH_DIFF,
    RED_TEAM,
    EVIDENCE_COVERAGE,
    IDENTITY_INTEGRITY,
    MUTATION_BYPASS
}
