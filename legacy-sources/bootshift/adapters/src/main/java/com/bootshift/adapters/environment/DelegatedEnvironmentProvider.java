package com.bootshift.adapters.environment;

import com.bootshift.core.util.Hashing;
import com.bootshift.ports.environment.EnvironmentProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DELEGATED environment provider.
 *
 * <p>Existing CI or infrastructure supplies some or all dependencies. The provider therefore cannot
 * assert that MUST_MATCH attributes it did not create are actually equal, and it says so: each such
 * attribute becomes an explicit equivalence gap unless the operator supplied externally attested
 * evidence for it.
 *
 * <p>Consequence, by design: a delegated run does not earn the same evidence strength as a managed
 * run for the same observations (spec section 7).
 */
public final class DelegatedEnvironmentProvider implements EnvironmentProvider {

    public static final String IMPLEMENTATION = "bootshift-delegated";
    public static final String VERSION = "1.0.0";

    private final Map<String, String> declaredAttributes;
    private final Map<String, String> attestedEvidence;
    private final Map<String, ProvisionedEnvironment> provisioned = new LinkedHashMap<>();

    public DelegatedEnvironmentProvider(Map<String, String> declaredAttributes,
                                        Map<String, String> attestedEvidence) {
        this.declaredAttributes = declaredAttributes;
        this.attestedEvidence = attestedEvidence;
    }

    @Override
    public Mode mode() {
        return Mode.DELEGATED;
    }

    @Override
    public String implementationName() {
        return IMPLEMENTATION;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ProvisionedEnvironment provision(String role, Map<String, String> requirements) {
        List<Attribute> attributes = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Map<String, String> endpoints = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : declaredAttributes.entrySet()) {
            Constraint constraint = classify(entry.getKey());
            attributes.add(new Attribute(entry.getKey(), entry.getValue(), constraint, "delegated-declaration"));
            if (constraint == Constraint.MUST_MATCH && !attestedEvidence.containsKey(entry.getKey())) {
                gaps.add("UNATTESTED_MUST_MATCH: " + entry.getKey()
                        + " was declared by the delegated environment but no attestation was supplied; "
                        + "the harness cannot enforce equivalence for this attribute.");
            }
        }
        for (Map.Entry<String, String> requirement : requirements.entrySet()) {
            if (requirement.getKey().startsWith("infrastructure.")) {
                String endpoint = declaredAttributes.get(requirement.getKey());
                if (endpoint == null) {
                    gaps.add("MISSING_INFRASTRUCTURE: " + requirement.getKey()
                            + " is required but the delegated environment did not declare an endpoint.");
                } else {
                    endpoints.put(requirement.getKey(), endpoint);
                }
            }
        }
        if (declaredAttributes.isEmpty()) {
            gaps.add("NO_DECLARATION: the delegated environment declared no attributes at all, so no "
                    + "equivalence guarantee of any kind can be recorded.");
        }

        boolean usable = !declaredAttributes.isEmpty();
        ProvisionedEnvironment environment = new ProvisionedEnvironment(Mode.DELEGATED, IMPLEMENTATION,
                VERSION, fingerprint(role, attributes), attributes, List.of(), gaps, endpoints, usable,
                usable ? null : "Delegated environment supplied no attributes");
        provisioned.put(role, environment);
        return environment;
    }

    @Override
    public List<EquivalenceCheck> compare(ProvisionedEnvironment oldEnv, ProvisionedEnvironment newEnv) {
        List<EquivalenceCheck> checks = new ArrayList<>();
        Map<String, Attribute> newIndex = new LinkedHashMap<>();
        newEnv.attributes().forEach(a -> newIndex.put(a.name(), a));
        for (Attribute attribute : oldEnv.attributes()) {
            Attribute counterpart = newIndex.get(attribute.name());
            String newValue = counterpart == null ? null : counterpart.value();
            boolean attested = attestedEvidence.containsKey(attribute.name());
            boolean equal = java.util.Objects.equals(attribute.value(), newValue);
            boolean satisfied = switch (attribute.constraint()) {
                case MUST_MATCH -> equal && attested;
                case EXPECTED_TO_DIFFER, UNCONSTRAINED -> true;
            };
            String detail = attribute.constraint() == Constraint.MUST_MATCH && equal && !attested
                    ? "values are equal but unattested: a delegated environment cannot self-certify a "
                      + "MUST_MATCH attribute"
                    : (satisfied ? "satisfied" : "MUST_MATCH attribute is not equivalent");
            checks.add(new EquivalenceCheck(attribute.name(), attribute.constraint(),
                    attribute.value(), newValue, satisfied, detail));
        }
        return checks;
    }

    @Override
    public void release(String role) {
        provisioned.remove(role);
    }

    /** MUST_MATCH set from spec section 15; anything version-related is expected to differ. */
    private static Constraint classify(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("locale") || lower.contains("timezone") || lower.contains("clock")
                || lower.contains("encoding") || lower.contains("seed")
                || lower.startsWith("infrastructure.")) {
            return Constraint.MUST_MATCH;
        }
        if (lower.contains("version") || lower.contains("jdk") || lower.contains("boot")
                || lower.contains("hibernate") || lower.contains("jackson") || lower.contains("container")) {
            return Constraint.EXPECTED_TO_DIFFER;
        }
        return Constraint.UNCONSTRAINED;
    }

    private static String fingerprint(String role, List<Attribute> attributes) {
        List<String> lines = new ArrayList<>();
        lines.add("role:" + role);
        attributes.forEach(a -> lines.add(a.name() + "=" + a.value() + "|" + a.constraint()));
        java.util.Collections.sort(lines);
        return Hashing.manifestHash(lines);
    }
}
