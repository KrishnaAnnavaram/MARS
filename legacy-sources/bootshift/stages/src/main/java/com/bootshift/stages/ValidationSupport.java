package com.bootshift.stages;

import com.bootshift.adapters.build.MavenBuildAdapter;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.NodeType;
import com.bootshift.ports.build.BuildSystemPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared execution helpers for the observation stages (Agent 04, 15 and 16).
 *
 * <p>Two behaviours live here because getting them wrong silently corrupts evidence:
 *
 * <ul>
 *   <li>coverage instrumentation must never be allowed to destroy the test observation. If the
 *       JaCoCo agent cannot attach, the tests are re-run without it and coverage is reported as
 *       unavailable with the reason - the alternative is a run that reports zero tests when the
 *       tests actually exist;</li>
 *   <li>runtime isolation settings must be derived from what the module <em>is</em>. Disabling the
 *       Eureka client on a module that is itself the Eureka server breaks it for a reason that has
 *       nothing to do with the application.</li>
 * </ul>
 */
public final class ValidationSupport {

    public static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.12";

    private ValidationSupport() {
    }

    /** Result of running a module's tests, including whether coverage could be measured. */
    public record TestRun(BuildSystemPort.ExecutionResult execution, boolean coverageAttempted,
                          boolean coverageUsable, String coverageUnavailableReason,
                          boolean retriedWithoutCoverage) {
    }

    /**
     * Runs the module test suite, measuring coverage when the agent can attach.
     *
     * <p>The agent is attached as a CLI goal rather than by editing the POM, because editing the
     * application before the baseline seal would violate R7 and editing it afterwards would change
     * what is being measured.
     */
    public static TestRun runTests(MavenBuildAdapter maven, Path moduleRoot, Path logSink,
                                   String javaHomeOverride) {
        Map<String, String> options = new LinkedHashMap<>();
        if (logSink != null) {
            options.put("bootshift.logSink", logSink.toString());
        }
        if (javaHomeOverride != null) {
            options.put("bootshift.javaHome", javaHomeOverride);
        }

        BuildSystemPort.ExecutionResult instrumented = maven.invoke(moduleRoot,
                List.of("-B", JACOCO + ":prepare-agent", "test", JACOCO + ":report"), options);

        if (instrumented.success()) {
            return new TestRun(instrumented, true, true, null, false);
        }

        String agentFailure = detectAgentFailure(moduleRoot);
        if (agentFailure == null) {
            // The tests themselves failed. That is a real observation, not an instrumentation problem.
            return new TestRun(instrumented, true, Files.isRegularFile(
                    moduleRoot.resolve("target/site/jacoco/jacoco.xml")),
                    "Test phase failed before the coverage report could be produced", false);
        }

        // Instrumentation broke the fork. Re-run without it so the test outcome is still observed.
        Map<String, String> plain = new LinkedHashMap<>(options);
        if (logSink != null) {
            plain.put("bootshift.logSink", logSink.getParent()
                    .resolve(logSink.getFileName() + ".no-coverage.log").toString());
        }
        BuildSystemPort.ExecutionResult retry = maven.invoke(moduleRoot, List.of("-B", "test"), plain);
        return new TestRun(retry, true, false,
                "Coverage instrumentation could not attach: " + agentFailure
                        + ". Tests were re-run without the agent so the test outcome is still observed, "
                        + "but no coverage number exists for this module.",
                true);
    }

    /**
     * Detects a JaCoCo agent attachment failure from Surefire dump streams.
     *
     * <p>Old Surefire versions do not late-evaluate the {@code argLine} property that
     * {@code prepare-agent} sets, which produces a malformed {@code -javaagent} argument and a dead
     * fork rather than a normal test failure.
     */
    public static String detectAgentFailure(Path moduleRoot) {
        Path reports = moduleRoot.resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) {
            return null;
        }
        try (var stream = Files.list(reports)) {
            for (Path file : stream.filter(p -> p.toString().endsWith("dumpstream")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("javaagent") || text.contains("processJavaStart")) {
                    return text.lines()
                            .filter(l -> l.contains("javaagent") || l.contains("processJavaStart"))
                            .findFirst().orElse("javaagent processing failed").trim();
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Runtime isolation settings for a module.
     *
     * <p>External participation is disabled so a service can start alone, but only where disabling it
     * is meaningful. A discovery server needs its own client infrastructure to bootstrap, so the
     * blanket switches are withheld for it; anything the harness therefore cannot isolate is reported
     * as an environment dependency rather than silently forced.
     */
    public static Map<String, String> runtimeSettings(String moduleId, ApplicationGraph graph,
                                                      BuildSystemPort.BuildModel buildModel) {
        Map<String, String> settings = new LinkedHashMap<>();
        boolean isDiscoveryServer = buildModel.dependencies().stream()
                .anyMatch(d -> d.module().equals(moduleId)
                        && d.artifactId().contains("eureka-server"));
        boolean isConfigServer = buildModel.dependencies().stream()
                .anyMatch(d -> d.module().equals(moduleId)
                        && d.artifactId().contains("config-server"));

        settings.put("spring.cloud.config.enabled", "false");
        settings.put("spring.cloud.config.fail-fast", "false");

        if (isDiscoveryServer) {
            // A Eureka server registers with itself; removing the client beans breaks its own
            // auto-configuration. Standalone mode is the supported way to run one in isolation.
            settings.put("eureka.client.register-with-eureka", "false");
            settings.put("eureka.client.fetch-registry", "false");
            settings.put("eureka.client.enabled", "true");
            settings.put("eureka.server.wait-time-in-ms-when-sync-empty", "0");
        } else {
            settings.put("eureka.client.enabled", "false");
            settings.put("eureka.client.register-with-eureka", "false");
            settings.put("eureka.client.fetch-registry", "false");
            settings.put("spring.cloud.discovery.enabled", "false");
        }
        if (isConfigServer) {
            // The config server reads from a remote Git repository; a native profile keeps the
            // context startable without network access to that repository.
            settings.put("spring.profiles.active", "native");
            settings.put("spring.cloud.config.server.native.search-locations", "classpath:/");
            settings.put("spring.cloud.config.enabled", "true");
        }
        return settings;
    }

    /**
     * Infrastructure requirements in the shape the environment provider expects.
     *
     * <p>Prefixed with {@code infrastructure.} so the provider knows to actually start something.
     * Callers previously computed the dependency list and then never asked for any of it, so nothing
     * was ever provisioned and every persistence dimension was NOT_COMPARED on every run.
     */
    public static Map<String, String> infrastructureRequirements(String moduleId,
                                                                 ApplicationGraph graph,
                                                                 BuildSystemPort.BuildModel model) {
        Map<String, String> requirements = new LinkedHashMap<>();
        for (String dependency : externalDependencies(moduleId, graph, model)) {
            if (com.bootshift.adapters.environment.ContainerProvisioner.catalogComponents()
                    .contains(dependency)) {
                requirements.put("infrastructure." + dependency, "required");
            }
        }
        return requirements;
    }

    /** External infrastructure a module needs but the harness may be unable to provide. */
    public static List<String> externalDependencies(String moduleId, ApplicationGraph graph,
                                                    BuildSystemPort.BuildModel buildModel) {
        List<String> dependencies = new ArrayList<>();
        buildModel.dependencies().stream()
                .filter(d -> d.module().equals(moduleId))
                .forEach(d -> {
                    if (d.artifactId().contains("data-mongodb")) {
                        dependencies.add("mongodb");
                    } else if (d.artifactId().contains("data-redis")) {
                        dependencies.add("redis");
                    } else if (d.artifactId().contains("kafka")) {
                        dependencies.add("kafka");
                    } else if (d.artifactId().contains("amqp")) {
                        dependencies.add("rabbitmq");
                    }
                });
        graph.nodesOfType(NodeType.EXTERNAL_SYSTEM).stream()
                .filter(n -> graph.incoming(n.getId()).stream()
                        .anyMatch(e -> e.getType() == EdgeType.CALLS_EXTERNAL_SERVICE
                                && e.getFrom().equals("MODULE:" + moduleId)))
                .forEach(n -> dependencies.add(n.getName()));
        return dependencies.stream().distinct().toList();
    }
}
