package com.bootshift.adapters.environment;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.environment.EnvironmentProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * MANAGED environment provider.
 *
 * <p>The harness owns the reproducible parts of the environment it can actually control in-process:
 * JDK, locale, timezone, clock strategy and the working directories. Where an OCI runtime is
 * available it is recorded as an additional isolation mechanism; where it is not, the provider says
 * so instead of pretending the environment was containerised.
 *
 * <p>Attributes are classified MUST_MATCH / EXPECTED_TO_DIFFER / UNCONSTRAINED exactly as the
 * Environment Equivalence Contract requires, and the classification - not the raw value - is what
 * decides whether a difference invalidates a differential result.
 */
public final class ManagedEnvironmentProvider implements EnvironmentProvider {

    public static final String IMPLEMENTATION = "bootshift-managed-jvm";
    public static final String VERSION = "1.0.0";

    private final Map<String, ProvisionedEnvironment> provisioned = new LinkedHashMap<>();
    private final Map<String, String> infrastructureOverrides;
    private final Map<String, List<ContainerProvisioner.Provisioned>> provisionedContainers =
            new LinkedHashMap<>();
    private ContainerProvisioner containerProvisioner;

    public ManagedEnvironmentProvider() {
        this(Map.of());
    }

    public ManagedEnvironmentProvider(Map<String, String> infrastructureOverrides) {
        this.infrastructureOverrides = infrastructureOverrides;
    }

    @Override
    public Mode mode() {
        return Mode.MANAGED;
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

        // Controlled and required to be identical on both sides.
        attributes.add(new Attribute("locale", "C", Constraint.MUST_MATCH, "harness-forced"));
        attributes.add(new Attribute("timezone", "UTC", Constraint.MUST_MATCH, "harness-forced"));
        attributes.add(new Attribute("clock.strategy", "system-utc", Constraint.MUST_MATCH, "harness-forced"));
        attributes.add(new Attribute("file.encoding", "UTF-8", Constraint.MUST_MATCH, "harness-forced"));
        // What is actually enforced, and only that. The allowlist governs the harness's own HTTP
        // client. It does not govern the child processes - Maven resolving dependencies, the
        // application opening a connection - because nothing in this deployment intercepts their
        // traffic. Recording "network.egress = allowlist" as though it covered everything claimed a
        // control that does not exist for the processes that matter most.
        attributes.add(new Attribute("network.egress.harness", "allowlist",
                Constraint.MUST_MATCH, "harness-http-client"));
        attributes.add(new Attribute("network.egress.child.processes", "UNRESTRICTED",
                Constraint.MUST_MATCH,
                "no mechanism in this deployment restricts egress from build tools or from the "
                        + "application under analysis"));

        // Expected to differ: this is the whole point of the migration.
        attributes.add(new Attribute("java.version", System.getProperty("java.version"),
                Constraint.EXPECTED_TO_DIFFER, "jvm"));
        attributes.add(new Attribute("java.vendor", System.getProperty("java.vendor"),
                Constraint.EXPECTED_TO_DIFFER, "jvm"));
        attributes.add(new Attribute("spring.boot.version", requirements.getOrDefault("spring.boot.version", "unknown"),
                Constraint.EXPECTED_TO_DIFFER, "build-model"));
        attributes.add(new Attribute("servlet.container", requirements.getOrDefault("servlet.container", "unknown"),
                Constraint.EXPECTED_TO_DIFFER, "build-model"));

        attributes.add(new Attribute("os.name", System.getProperty("os.name"),
                Constraint.UNCONSTRAINED, "host"));
        attributes.add(new Attribute("available.processors",
                String.valueOf(Runtime.getRuntime().availableProcessors()), Constraint.UNCONSTRAINED, "host"));

        List<String> gaps = new ArrayList<>();
        Map<String, String> endpoints = new LinkedHashMap<>(infrastructureOverrides);

        // A binary on PATH is not a runtime: Docker Desktop is routinely installed with its engine
        // stopped. Availability is decided by asking the daemon.
        ContainerProvisioner containers = provisioner();
        String oci = containers.runtime();
        if (oci == null) {
            gaps.add("NO_OCI_RUNTIME: no rootless OCI runtime daemon (podman/docker/nerdctl) "
                    + "answered, so infrastructure dependencies cannot be provisioned by the harness. "
                    + "Any dimension requiring a database, broker or cache is recorded as "
                    + "NOT_COMPARED rather than assumed equivalent.");
            attributes.add(new Attribute("container.runtime", "none", Constraint.MUST_MATCH,
                    "daemon-probe"));
        } else {
            attributes.add(new Attribute("container.runtime", oci, Constraint.MUST_MATCH,
                    "daemon-probe"));
        }

        // Provision exactly what was asked for, and nothing else. Starting a database the graph
        // never mentioned costs minutes and proves nothing.
        for (Map.Entry<String, String> requirement : requirements.entrySet()) {
            if (!requirement.getKey().startsWith("infrastructure.")) {
                continue;
            }
            String component = requirement.getKey().substring("infrastructure.".length());
            if (endpoints.containsKey(component)) {
                // Supplied externally; the harness did not create it and says so.
                attributes.add(new Attribute("infrastructure." + component + ".source", "EXTERNAL",
                        Constraint.MUST_MATCH, "operator-supplied"));
                continue;
            }
            if (oci == null) {
                gaps.add("UNPROVISIONED: " + component + " is required by this module but no "
                        + "container runtime is available to supply it");
                continue;
            }
            ContainerProvisioner.Provisioned provisionedContainer =
                    containers.provision(component, role);
            provisionedContainers.computeIfAbsent(role, k -> new ArrayList<>())
                    .add(provisionedContainer);
            if (provisionedContainer.usable()) {
                endpoints.put(component, provisionedContainer.endpoint());
                // The digest, not just the tag: two sides that pulled different builds of the same
                // tag are two different environments.
                attributes.add(new Attribute("infrastructure." + component + ".image",
                        provisionedContainer.image() + ":" + provisionedContainer.tag(),
                        Constraint.MUST_MATCH, "container-provisioner"));
                attributes.add(new Attribute("infrastructure." + component + ".digest",
                        provisionedContainer.digest(), Constraint.MUST_MATCH,
                        "container-provisioner"));
            } else {
                gaps.add("UNPROVISIONED: " + component + " could not be started: "
                        + provisionedContainer.unusableReason());
            }
        }

        String fingerprint = fingerprint(role, attributes);
        ProvisionedEnvironment environment = new ProvisionedEnvironment(Mode.MANAGED, IMPLEMENTATION,
                VERSION, fingerprint, attributes, List.of(), gaps, endpoints, true, null);
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
            boolean satisfied = switch (attribute.constraint()) {
                case MUST_MATCH -> java.util.Objects.equals(attribute.value(), newValue);
                case EXPECTED_TO_DIFFER, UNCONSTRAINED -> true;
            };
            String detail = switch (attribute.constraint()) {
                case MUST_MATCH -> satisfied ? "identical on both sides"
                        : "MUST_MATCH attribute differs; differential results for affected dimensions "
                          + "cannot be trusted";
                case EXPECTED_TO_DIFFER -> "difference is expected and does not invalidate comparison";
                case UNCONSTRAINED -> "not part of the equivalence contract";
            };
            checks.add(new EquivalenceCheck(attribute.name(), attribute.constraint(),
                    attribute.value(), newValue, satisfied, detail));
        }
        return checks;
    }

    /** Lazily created so a run that never needs infrastructure never probes for a runtime. */
    private synchronized ContainerProvisioner provisioner() {
        if (containerProvisioner == null) {
            containerProvisioner = new ContainerProvisioner();
        }
        return containerProvisioner;
    }

    /** Everything this provider started, for the evidence record. */
    public Map<String, List<ContainerProvisioner.Provisioned>> provisionedContainers() {
        return provisionedContainers;
    }

    @Override
    public void release(String role) {
        provisioned.remove(role);
        provisionedContainers.remove(role);
        // Deterministic cleanup. A container left running holds a port that the next run needs.
        if (containerProvisioner != null && provisionedContainers.isEmpty()) {
            List<String> failures = containerProvisioner.release();
            failures.forEach(failure ->
                    org.slf4j.LoggerFactory.getLogger(ManagedEnvironmentProvider.class)
                            .warn("Could not remove container {}", failure));
        }
    }

    /**
     * Detects a rootless OCI runtime whose daemon actually answers.
     *
     * <p>The previous implementation returned the first candidate whose binary was on {@code PATH}.
     * On a machine with Docker Desktop installed but stopped that reported
     * {@code container.runtime=docker} while nothing could be provisioned - claiming an isolation
     * mechanism the run did not have.
     */
    public static String detectOciRuntime() {
        return ContainerProvisioner.detectWorkingRuntime(new ProcessRunner(
                java.util.Set.of("podman", "podman.exe", "docker", "docker.exe",
                        "nerdctl", "nerdctl.exe"), 4000));
    }

    private static String fingerprint(String role, List<Attribute> attributes) {
        List<String> lines = new ArrayList<>();
        lines.add("role:" + role);
        attributes.stream()
                .filter(a -> a.constraint() != Constraint.UNCONSTRAINED)
                .forEach(a -> lines.add(a.name() + "=" + a.value() + "|" + a.constraint()));
        java.util.Collections.sort(lines);
        return Hashing.manifestHash(lines);
    }

    /** Applied to every child process so both sides observe the same clock and locale. */
    public static Map<String, String> deterministicProcessEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TZ", "UTC");
        env.put("LANG", "C");
        env.put("LC_ALL", "C");
        env.put("JAVA_TOOL_OPTIONS", "-Duser.timezone=UTC -Dfile.encoding=UTF-8 -Duser.language=en");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        return env;
    }
}
