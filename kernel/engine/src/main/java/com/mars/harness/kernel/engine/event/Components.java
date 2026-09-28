package com.mars.harness.kernel.engine.event;

/**
 * The names under which executors appear in execution events. Each one is a real class or port
 * that performs the work; none is an invented "agent". Tool-backed components also carry the
 * concrete adapter's class name, so a simulated tool is never mistaken for the real one.
 */
public final class Components {

    public static final String ENGINE = "Kernel / Harness Engine";
    public static final String BOOTSHIFT = "Kernel / Bootshift Bridge";
    public static final String INVENTORY = "Kernel / Inventory (Bootshift FileRegistry)";
    public static final String IDENTITY = "Kernel / Identity Registry";
    public static final String GRAPH = "Kernel / Canonical Graph Builder";
    public static final String BASELINE = "Kernel / Baseline Seal";
    public static final String DISCOVERY = "Kernel / Discovery";
    public static final String SECURITY_DISCOVERY = "Security / Discovery (scanner, anchoring)";
    public static final String MIGRATION_ADVISOR = "Migration / Migration Advisor";
    public static final String SEQUENCE_ADVISOR = "Kernel / Sequence Advisor";
    public static final String ADVANCER = "Kernel / Run Advancer";
    public static final String MIGRATION_ENGINE = "Migration / Reference Pack Engine";
    public static final String REMEDIATION_PLANNER = "Security / Remediation Planner (catalog, KB, research routing)";
    public static final String FIX_VERIFIER = "Security / Fix Verifier + Merge Arbiter";
    public static final String GATEWAY = "Kernel / Mutation Gateway";
    public static final String VALIDATOR = "Kernel / Unified Validator";
    public static final String VERDICT = "Kernel / Verdict Calculator";
    public static final String APPROVALS = "Kernel / Approval Port";

    private Components() {
    }

    /** A tool-backed component: {@code Kernel / Build (MavenBuildRunner)}. */
    public static String tool(String role, Object adapter) {
        return "Kernel / " + role + " (" + (adapter == null ? "none" : adapter.getClass().getSimpleName()) + ")";
    }
}
