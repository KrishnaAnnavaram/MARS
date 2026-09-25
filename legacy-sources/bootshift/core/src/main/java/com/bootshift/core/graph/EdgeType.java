package com.bootshift.core.graph;

import java.util.EnumSet;
import java.util.Set;

/**
 * Semantic edge kinds (spec section 13).
 *
 * <p>Everything is deliberately NOT collapsed into DEPENDS_ON: blast radius answers must be able to
 * explain <em>why</em> a node is included, and that explanation is the edge kind.
 *
 * <p>Edges are additionally split into two evidence layers (R27): those a static analyser can
 * assert, and those only a running application can assert.
 */
public enum EdgeType {
    // ---- static / semantic layer -------------------------------------------------
    CONTAINS,
    DECLARES,
    IMPORTS,
    CALLS,
    EXTENDS,
    IMPLEMENTS,
    USES_TYPE,
    INJECTS,
    DEPENDS_ON,
    DEPENDS_ON_LIBRARY,
    DECLARES_BEAN,
    CONFIGURES,
    HANDLES_ENDPOINT,
    CALLS_SERVICE,
    CALLS_REPOSITORY,
    MANAGES_ENTITY,
    MAPS_TO_TABLE,
    USES_CONFIG_PROPERTY,
    ACTIVATED_BY_PROFILE,
    COVERED_BY_TEST,
    CALLS_EXTERNAL_SERVICE,
    REGISTERS_WITH_DISCOVERY,
    READS_FROM_CONFIG_SERVER,
    PUBLISHES_TO,
    CONSUMES_FROM,

    // ---- runtime observation layer ----------------------------------------------
    ACTUALLY_INJECTED,
    ACTIVE_UNDER_PROFILE,
    ACTUALLY_HANDLES_ENDPOINT,
    ACTUALLY_BINDS_PROPERTY,
    ACTUALLY_CALLS_EXTERNAL,
    ACTUALLY_PUBLISHES_TO,
    ACTUALLY_CONSUMES_FROM;

    private static final Set<EdgeType> RUNTIME = EnumSet.of(
            ACTUALLY_INJECTED, ACTIVE_UNDER_PROFILE, ACTUALLY_HANDLES_ENDPOINT,
            ACTUALLY_BINDS_PROPERTY, ACTUALLY_CALLS_EXTERNAL, ACTUALLY_PUBLISHES_TO,
            ACTUALLY_CONSUMES_FROM);

    /** True when only a running application can produce this edge. */
    public boolean isRuntimeObserved() {
        return RUNTIME.contains(this);
    }

    public boolean isStatic() {
        return !isRuntimeObserved();
    }
}
