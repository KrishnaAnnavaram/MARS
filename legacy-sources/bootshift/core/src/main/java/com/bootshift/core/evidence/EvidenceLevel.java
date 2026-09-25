package com.bootshift.core.evidence;

/**
 * Evidence ladder (spec section 38).
 *
 * <p>A level is always asserted <em>per dimension</em> and always accompanied by a coverage
 * statement. "Security = E4" on its own is not a legal assertion in this harness; see
 * {@link CoverageStatement}.
 */
public enum EvidenceLevel {

    /** Source, build and framework state inventoried. */
    E0("Inventory known"),

    /** Structure and graph known; migration changes structurally traceable. */
    E1("Structural understanding"),

    /** Target dependencies resolve and the build compiles. */
    E2("Build and API validity"),

    /** Existing and targeted tests pass relative to sealed baseline debt. */
    E3("Test contract"),

    /** Required OLD-vs-NEW migration-sensitive scenarios compared. */
    E4("Migration-sensitive behavioural evidence"),

    /** Representative integrations validated in a representative environment plus human judgment. */
    E5("Production-like validation and human judgment");

    private final String title;

    EvidenceLevel(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public int rank() {
        return ordinal();
    }

    public boolean atLeast(EvidenceLevel other) {
        return rank() >= other.rank();
    }

    public static EvidenceLevel min(EvidenceLevel a, EvidenceLevel b) {
        return a.rank() <= b.rank() ? a : b;
    }
}
