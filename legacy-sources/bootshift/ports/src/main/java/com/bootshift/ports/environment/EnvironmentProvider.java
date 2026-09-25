package com.bootshift.ports.environment;

import java.util.List;
import java.util.Map;

/**
 * Environment provisioning contract (spec section 7).
 *
 * <p>MANAGED means the harness owns creation of reproducible dependencies and can enforce the
 * Environment Equivalence Contract directly. DELEGATED means existing CI or infrastructure supplies
 * some or all of them; the harness then records which guarantees it could not enforce, and evidence
 * strength is reduced accordingly rather than silently assumed equal.
 */
public interface EnvironmentProvider {

    enum Mode {
        MANAGED, DELEGATED
    }

    /** Classification of one environment attribute in the equivalence contract. */
    enum Constraint {
        MUST_MATCH, EXPECTED_TO_DIFFER, UNCONSTRAINED
    }

    record Attribute(String name, String value, Constraint constraint, String source) {
    }

    record EquivalenceCheck(String attribute, Constraint constraint, String oldValue, String newValue,
                            boolean satisfied, String detail) {
    }

    record ProvisionedEnvironment(Mode mode, String providerImplementation, String providerVersion,
                                  String fingerprint, List<Attribute> attributes,
                                  List<EquivalenceCheck> checks, List<String> equivalenceGaps,
                                  Map<String, String> endpoints, boolean usable, String unusableReason) {
    }

    Mode mode();

    String implementationName();

    String version();

    /** Provisions or adopts an environment for the given role, e.g. runtime-old or runtime-new. */
    ProvisionedEnvironment provision(String role, Map<String, String> requirements);

    /** Compares two provisioned environments against the contract. */
    List<EquivalenceCheck> compare(ProvisionedEnvironment oldEnv, ProvisionedEnvironment newEnv);

    void release(String role);
}
