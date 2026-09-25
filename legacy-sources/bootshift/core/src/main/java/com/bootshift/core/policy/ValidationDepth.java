package com.bootshift.core.policy;

/**
 * Validation depth ladder (spec sections 22 and 35).
 *
 * <p>Depth is computed once by the Planner and frozen into the edge plan. Execution reads the frozen
 * depth; it never invents its own (R16).
 */
public enum ValidationDepth {

    /** Compile only. Legal only for trivially reversible edges under an explicit policy. */
    BUILD_ONLY(0),

    /** Compile plus the application test suite. */
    BUILD_AND_TESTS(1),

    /** Adds Spring context startup, configuration binding and property provenance. */
    BUILD_TESTS_RUNTIME(2),

    /** Adds OLD-vs-NEW differential for the dimensions the impact analysis flagged. */
    IMPACTED_DIFFERENTIAL(3),

    /** Adds every required differential dimension whose environment equivalence can be satisfied. */
    FULL_DIFFERENTIAL(4);

    private final int rank;

    ValidationDepth(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean requiresTests() {
        return rank >= BUILD_AND_TESTS.rank;
    }

    public boolean requiresRuntime() {
        return rank >= BUILD_TESTS_RUNTIME.rank;
    }

    public boolean requiresDifferential() {
        return rank >= IMPACTED_DIFFERENTIAL.rank;
    }

    public static ValidationDepth max(ValidationDepth... values) {
        ValidationDepth best = BUILD_ONLY;
        for (ValidationDepth value : values) {
            if (value != null && value.rank > best.rank) {
                best = value;
            }
        }
        return best;
    }

    public ValidationDepth escalate(int levels) {
        int target = Math.min(FULL_DIFFERENTIAL.rank, rank + levels);
        for (ValidationDepth value : values()) {
            if (value.rank == target) {
                return value;
            }
        }
        return FULL_DIFFERENTIAL;
    }
}
