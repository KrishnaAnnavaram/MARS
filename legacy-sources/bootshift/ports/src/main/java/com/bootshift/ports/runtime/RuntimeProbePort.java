package com.bootshift.ports.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runtime observation of a Spring application (Agent 04 baseline enrichment, Agent 16).
 *
 * <p>Startup success is one observation, not migration success (R19). Every observation carries the
 * evidence reference that produced it so the runtime graph never launders an inference as a fact.
 */
public interface RuntimeProbePort {

    enum Dimension {
        CONTEXT, BEANS, CONDITIONS, PROFILES, REQUEST_MAPPINGS, HEALTH, CONFIG_BINDING,
        SECURITY_FILTER_CHAIN, PERSISTENCE, MESSAGING, SCHEDULING, BATCH, EXTERNAL_CLIENT,
        SERIALIZATION
    }

    /** One property as the application actually bound it, with provenance (spec section 32). */
    record BoundProperty(String canonicalKey, String sourceFile, String sourceType, String targetType,
                         String targetField, boolean bound, boolean defaulted, boolean deprecated,
                         String replacement, boolean sensitive) {
    }

    record Observation(Dimension dimension, String subject, String detail, Map<String, Object> data,
                       String evidenceRef, boolean successful) {
    }

    record ProbeResult(String module, boolean started, String failureReason, List<Observation> observations,
                       List<BoundProperty> boundProperties, List<String> unobservable,
                       String environmentFingerprint, long durationMillis) {
    }

    String name();

    /** True when the probe can actually run in the current environment. */
    boolean available(Path moduleRoot);

    ProbeResult probe(Path repositoryRoot, Path moduleRoot, String moduleId, Map<String, String> settings);
}
