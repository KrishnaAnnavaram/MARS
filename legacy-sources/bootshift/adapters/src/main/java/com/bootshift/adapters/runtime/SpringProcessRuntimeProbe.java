package com.bootshift.adapters.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.util.Json;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.ports.characterization.ScenarioObservation;
import com.bootshift.ports.runtime.RuntimeProbePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime probe that actually starts the application and observes it (Agent 04 enrichment, Agent 16).
 *
 * <p>The probe launches the packaged application in a child process, waits for the Spring context to
 * report readiness, then interrogates whichever surfaces are reachable: Actuator endpoints when the
 * application exposes them, and the startup log when it does not.
 *
 * <p>Crucially it does not fake anything. A module that cannot start because MongoDB or a Config
 * Server is unavailable produces {@code started=false} and a populated {@code unobservable} list,
 * which becomes a blind spot in the evidence manifest instead of an assumed pass (R19).
 */
public final class SpringProcessRuntimeProbe implements RuntimeProbePort {

    private static final Logger LOG = LoggerFactory.getLogger(SpringProcessRuntimeProbe.class);

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final ProcessRunner runner;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final Path logRoot;

    public SpringProcessRuntimeProbe(Path logRoot) {
        this(new ProcessRunner(), logRoot);
    }

    public SpringProcessRuntimeProbe(ProcessRunner runner, Path logRoot) {
        this.runner = runner;
        this.logRoot = logRoot;
    }

    @Override
    public String name() {
        return "spring-process-probe";
    }

    @Override
    public boolean available(Path moduleRoot) {
        return findArtifact(moduleRoot) != null;
    }

    /** Locates a packaged jar or war produced by a previous build. */
    public static Path findArtifact(Path moduleRoot) {
        Path target = moduleRoot.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (var stream = Files.list(target)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.endsWith(".jar") || name.endsWith(".war"))
                                && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
                                && !name.endsWith(".original");
                    })
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().length()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ProbeResult probe(Path repositoryRoot, Path moduleRoot, String moduleId,
                             Map<String, String> settings) {
        long start = System.currentTimeMillis();
        List<Observation> observations = new ArrayList<>();
        List<BoundProperty> bound = new ArrayList<>();
        List<String> unobservable = new ArrayList<>();

        Path artifact = findArtifact(moduleRoot);
        if (artifact == null) {
            unobservable.addAll(allDimensionNames(
                    "No packaged artifact for " + moduleId + "; the module was never successfully built"));
            return new ProbeResult(moduleId, false,
                    "No packaged artifact available - build the module before runtime validation",
                    observations, bound, unobservable, settings.getOrDefault("fingerprint", "unknown"),
                    System.currentTimeMillis() - start);
        }

        int port = Integer.parseInt(settings.getOrDefault("server.port", "0"));
        if (port == 0) {
            port = freePort();
        }
        String baseUrl = "http://127.0.0.1:" + port;

        List<String> command = new ArrayList<>();
        // The exact java binary the edge froze, passed explicitly. Resolving "java" from the harness
        // process meant the application was observed on whichever JDK happened to be running
        // Bootshift, which is not the toolchain any plan selected.
        String javaExecutable = settings.get("bootshift.javaExecutable");
        if (javaExecutable == null || javaExecutable.isBlank()) {
            String javaHome = settings.get("bootshift.javaHome");
            javaExecutable = javaHome == null ? null : javaHome + "/bin/java"
                    + (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                        .contains("win") ? ".exe" : "");
        }
        if (javaExecutable == null || !Files.isRegularFile(Path.of(javaExecutable))) {
            String fallback = ProcessRunner.jdkTool("java");
            unobservable.add("TOOLCHAIN: no explicit java executable was supplied for " + moduleId
                    + "; the observation would describe the harness JVM rather than the frozen edge "
                    + "toolchain");
            javaExecutable = fallback;
        }
        command.add(javaExecutable == null ? "java" : javaExecutable);
        command.add("-Dserver.port=" + port);
        command.add("-Dmanagement.server.port=" + port);
        command.add("-Dmanagement.endpoints.web.exposure.include=*");
        command.add("-Dmanagement.endpoint.health.show-details=always");
        command.add("-Dmanagement.endpoint.configprops.show-values=NEVER");
        command.add("-Dspring.main.banner-mode=off");
        command.add("-Duser.timezone=UTC");
        command.add("-Dfile.encoding=UTF-8");
        // Spring Cloud Config is NOT disabled here. Forcing it off globally changes what the
        // application binds, which is the very thing a configuration comparison measures, and it did
        // so identically on both sides so the difference never showed up. Whether to substitute a
        // controlled configuration source is the caller's decision, taken per module, and the
        // resulting evidence is qualified accordingly.
        List<String> appliedIsolation = new ArrayList<>();
        settings.forEach((key, value) -> {
            if (key.startsWith("jvm.arg.")) {
                command.add(value);
            } else if (key.startsWith("spring.") || key.startsWith("eureka.")
                    || key.startsWith("management.")) {
                command.add("-D" + key + "=" + value);
                appliedIsolation.add(key + "=" + value);
            }
        });
        String profile = settings.get("spring.profiles.active");
        if (profile != null) {
            command.add("-Dspring.profiles.active=" + profile);
        }
        command.add("-jar");
        command.add(artifact.toAbsolutePath().toString());

        Map<String, String> childEnvironment = new LinkedHashMap<>();
        String javaHome = settings.get("bootshift.javaHome");
        if (javaHome != null && !javaHome.isBlank()) {
            childEnvironment.put("JAVA_HOME", javaHome);
        }

        Path logFile = logRoot.resolve(moduleId.replaceAll("[^A-Za-z0-9._-]", "_") + "-runtime.log");
        ProcessHandleResult handle = launch(command, moduleRoot, logFile, childEnvironment);
        if (handle.process() == null) {
            unobservable.addAll(allDimensionNames("Process could not be started: " + handle.failure()));
            return new ProbeResult(moduleId, false, handle.failure(), observations, bound, unobservable,
                    settings.getOrDefault("fingerprint", "unknown"), System.currentTimeMillis() - start);
        }

        boolean started = false;
        boolean shutdownClean = false;
        String failureReason = null;
        Readiness readiness = new Readiness(false, "NOT_ATTEMPTED", null);
        try {
            readiness = waitForReadiness(baseUrl, handle.process(), logFile);
            started = readiness.ready();
            if (started) {
                Map<String, Object> startupFacts = new LinkedHashMap<>();
                startupFacts.put("base_url", baseUrl);
                startupFacts.put("artifact", artifact.getFileName().toString());
                startupFacts.put("artifact_sha256", hashOf(artifact));
                startupFacts.put("java_executable", javaExecutable);
                startupFacts.put("java_home", settings.getOrDefault("bootshift.javaHome", "<inherited>"));
                startupFacts.put("port", port);
                startupFacts.put("profile", profile == null ? "<none>" : profile);
                startupFacts.put("readiness_contract", readiness.contract());
                startupFacts.put("health_status", healthStatus(baseUrl));
                startupFacts.put("readiness_detail", readiness.detail());
                startupFacts.put("isolation_settings_applied", appliedIsolation);
                startupFacts.put("environment_fingerprint",
                        settings.getOrDefault("fingerprint", "unknown"));
                observations.add(new Observation(Dimension.CONTEXT, moduleId,
                        "Spring application context started and responded on " + baseUrl,
                        startupFacts, null, true));
                collect(baseUrl, "/actuator/health", Dimension.HEALTH, moduleId, observations, unobservable);
                collect(baseUrl, "/actuator/beans", Dimension.BEANS, moduleId, observations, unobservable);
                collect(baseUrl, "/actuator/conditions", Dimension.CONDITIONS, moduleId, observations, unobservable);
                collect(baseUrl, "/actuator/env", Dimension.CONFIG_BINDING, moduleId, observations, unobservable);
                collect(baseUrl, "/actuator/mappings", Dimension.REQUEST_MAPPINGS, moduleId, observations, unobservable);
                collectConfigProps(baseUrl, moduleId, observations, bound, unobservable);
            } else {
                failureReason = readiness.contract() + ": " + summarizeFailure(logFile, handle.process());
                unobservable.addAll(allDimensionNames(
                        "Application did not reach readiness: " + failureReason));
            }
        } finally {
            // The whole descendant tree. A Spring Boot application forks nothing by default, but a
            // war deployment, a Testcontainers-style helper or a shutdown hook that spawns one all
            // leave a process holding the port, and the next module then fails to bind it.
            ProcessRunner.terminateTree(handle.process(), Duration.ofSeconds(20));
            shutdownClean = !handle.process().isAlive();
        }

        if (!shutdownClean) {
            unobservable.add("SHUTDOWN: the application process did not terminate cleanly for "
                    + moduleId + "; a surviving process can hold the port for the next observation");
        }
        return new ProbeResult(moduleId, started, failureReason, observations, bound, unobservable,
                settings.getOrDefault("fingerprint", "unknown"), System.currentTimeMillis() - start);
    }

    /**
     * Starts the application, runs a set of characterization scenarios against it, and stops it.
     *
     * <p>This is the method that turns a contract into evidence, and it is deliberately the same code
     * path for both sides of the migration: identical scenarios, identical capture, identical
     * normalization. The only difference between OLD and NEW is which packaged artifact is started.
     */
    public ScenarioRun runScenarios(Path moduleRoot, String moduleId, List<Scenario> scenarios,
                                    ScenarioObservation.Side side, Map<String, String> settings) {
        List<ScenarioObservation> observations = new ArrayList<>();
        Path artifact = findArtifact(moduleRoot);
        if (artifact == null) {
            scenarios.forEach(scenario -> observations.add(ScenarioObservation.notExecuted(
                    scenario.scenarioId(), side,
                    "No packaged artifact for " + moduleId + "; nothing to execute against")));
            return new ScenarioRun(false, "no packaged artifact", observations, null);
        }

        // One start for the whole batch. An earlier version called probe() first to establish
        // readiness and then started the application again for the scenarios, which doubled a
        // ninety-second startup for every module and observed a different process than the one it
        // had checked.
        return executeAgainstRunningApplication(moduleRoot, moduleId, scenarios, side, settings,
                observations, null);
    }

    /** The outcome of a scenario batch, including the startup evidence that made it possible. */
    public record ScenarioRun(boolean started, String failureReason,
                              List<ScenarioObservation> observations, ProbeResult startup) {
    }

    private ScenarioRun executeAgainstRunningApplication(Path moduleRoot, String moduleId,
                                                         List<Scenario> scenarios,
                                                         ScenarioObservation.Side side,
                                                         Map<String, String> settings,
                                                         List<ScenarioObservation> observations,
                                                         ProbeResult startup) {
        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port;
        Map<String, String> scenarioSettings = new LinkedHashMap<>(settings);
        scenarioSettings.put("server.port", String.valueOf(port));

        Path artifact = findArtifact(moduleRoot);
        List<String> command = new ArrayList<>();
        String javaExecutable = settings.get("bootshift.javaExecutable");
        if (javaExecutable == null || javaExecutable.isBlank()
                || !Files.isRegularFile(Path.of(javaExecutable))) {
            javaExecutable = ProcessRunner.jdkTool("java");
        }
        command.add(javaExecutable == null ? "java" : javaExecutable);
        command.add("-Dserver.port=" + port);
        command.add("-Dmanagement.server.port=" + port);
        command.add("-Dmanagement.endpoints.web.exposure.include=*");
        command.add("-Dspring.main.banner-mode=off");
        command.add("-Duser.timezone=UTC");
        command.add("-Dfile.encoding=UTF-8");
        settings.forEach((key, value) -> {
            if (key.startsWith("spring.") || key.startsWith("eureka.") || key.startsWith("management.")) {
                command.add("-D" + key + "=" + value);
            }
        });
        command.add("-jar");
        command.add(artifact.toAbsolutePath().toString());

        Map<String, String> childEnvironment = new LinkedHashMap<>();
        String javaHome = settings.get("bootshift.javaHome");
        if (javaHome != null && !javaHome.isBlank()) {
            childEnvironment.put("JAVA_HOME", javaHome);
        }
        Path logFile = logRoot.resolve(moduleId.replaceAll("[^A-Za-z0-9._-]", "_")
                + "-scenarios-" + side.name().toLowerCase(java.util.Locale.ROOT) + ".log");
        ProcessHandleResult handle = launch(command, moduleRoot, logFile, childEnvironment);
        if (handle.process() == null) {
            scenarios.forEach(scenario -> observations.add(ScenarioObservation.notExecuted(
                    scenario.scenarioId(), side, "Could not start: " + handle.failure())));
            return new ScenarioRun(false, handle.failure(), observations, startup);
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("fingerprint", settings.getOrDefault("fingerprint", "unknown"));
        environment.put("java_executable", String.valueOf(javaExecutable));
        environment.put("artifact", artifact.getFileName().toString());
        environment.put("side", side.name());

        try {
            Readiness readiness = waitForReadiness(baseUrl, handle.process(), logFile);
            if (!readiness.ready()) {
                String reason = readiness.contract() + ": " + readiness.detail();
                scenarios.forEach(scenario -> observations.add(ScenarioObservation.notExecuted(
                        scenario.scenarioId(), side, reason)));
                return new ScenarioRun(false, reason, observations, startup);
            }
            ScenarioHttpExecutor executor = new ScenarioHttpExecutor();
            for (Scenario scenario : scenarios) {
                observations.add(executor.execute(scenario, baseUrl, side, environment));
            }
            return new ScenarioRun(true, null, observations, startup);
        } finally {
            ProcessRunner.terminateTree(handle.process(), Duration.ofSeconds(20));
        }
    }

    private static String hashOf(Path file) {
        try {
            return com.bootshift.core.util.Hashing.sha256File(file);
        } catch (IOException e) {
            return null;
        }
    }

    private record ProcessHandleResult(Process process, String failure) {
    }

    /**
     * Starts the application through the controlled execution interface.
     *
     * <p>This used to construct its own {@code ProcessBuilder}. That gave the one process in the
     * harness which runs untrusted application code for ninety seconds, opens a listening port and
     * connects to whatever the configuration names, none of the controls every other process gets:
     * no executable allowlist, the harness's entire environment including its credentials, and a
     * termination that killed only the direct child.
     */
    private ProcessHandleResult launch(List<String> command, Path workingDirectory, Path logFile,
                                       Map<String, String> environment) {
        ProcessRunner.Handle handle = runner.start(command, workingDirectory, environment, logFile);
        return new ProcessHandleResult(handle.process(), handle.failure());
    }

    /** What established readiness, so the evidence says how it was decided rather than just that it was. */
    public record Readiness(boolean ready, String contract, String detail) {
    }

    /**
     * Waits for a positive readiness signal.
     *
     * <p>The previous contract accepted any HTTP status below 500 on {@code /} as proof of
     * readiness. A 404 from a container that has bound the port but not finished refreshing the
     * context satisfies that, and so does a 401 from a security filter chain installed before the
     * application beans exist. Both were recorded as "started", and everything downstream then
     * compared an application that was not running yet.
     *
     * <p>Readiness now requires one of three positive signals, in descending order of strength:
     * Actuator health reporting UP, the startup log line the framework prints only after the context
     * has refreshed <em>and</em> the port answering, or a mapped endpoint returning a non-error
     * status. A 404 or a 401 alone is explicitly not enough.
     */
    private Readiness waitForReadiness(String baseUrl, Process process, Path logFile) {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                return new Readiness(false, "PROCESS_EXITED",
                        "The application process exited before readiness");
            }
            java.util.Optional<String> health = get(baseUrl + "/actuator/health");
            if (health.isPresent()) {
                try {
                    JsonNode node = Json.parse(health.get());
                    String status = node.path("status").asText("");
                    if ("UP".equals(status)) {
                        return new Readiness(true, "ACTUATOR_HEALTH_UP",
                                "/actuator/health reported status UP");
                    }
                    if (!status.isBlank()) {
                        // A health endpoint that answers at all proves the context refreshed and the
                        // application is serving: only a live Spring context produces this document.
                        // The status itself is an OBSERVATION about downstream availability, not a
                        // statement that the application failed to start, and treating DOWN as "did
                        // not start" discards every HTTP, security and serialization behaviour the
                        // application is perfectly capable of demonstrating without its database.
                        return new Readiness(true, "ACTUATOR_HEALTH_RESPONDING_" + status,
                                "/actuator/health reported status " + status
                                        + ". The context is up and serving; the status reflects a "
                                        + "downstream dependency and is recorded as an observation "
                                        + "rather than treated as a startup failure.");
                    }
                } catch (RuntimeException e) {
                    // Not JSON: fall through to the other signals rather than guessing.
                }
            }
            if (logIndicatesStarted(logFile) && portAnswers(baseUrl)) {
                return new Readiness(true, "STARTUP_LOG_AND_PORT",
                        "The framework logged context startup and the port answers");
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Readiness(false, "INTERRUPTED", "Readiness wait was interrupted");
            }
        }
        return new Readiness(false, "TIMEOUT",
                "No positive readiness signal within " + STARTUP_TIMEOUT
                        + ". A 404 or a 401 from a bound port is not readiness: the container can be "
                        + "listening before the context has refreshed.");
    }

    /**
     * True when the port answers with something that is not merely a container-level error.
     *
     * <p>404 and 401 are excluded on purpose: both are answers a servlet container gives before the
     * application is meaningfully up.
     */
    private boolean portAnswers(String baseUrl) {
        java.util.Optional<Integer> status = statusOf(baseUrl + "/");
        if (status.isEmpty()) {
            return false;
        }
        int code = status.get();
        return code < 400 || (code >= 400 && code != 404 && code != 401 && code < 500);
    }

    private java.util.Optional<Integer> statusOf(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            return java.util.Optional.of(
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private boolean logIndicatesStarted(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return false;
            }
            String text = Files.readString(logFile, StandardCharsets.UTF_8);
            return text.contains("Started ") && text.contains(" in ") && text.contains(" seconds");
        } catch (IOException e) {
            return false;
        }
    }

    private java.util.Optional<String> get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 500
                    ? java.util.Optional.of(response.body()) : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private void collect(String baseUrl, String path, Dimension dimension, String moduleId,
                         List<Observation> observations, List<String> unobservable) {
        java.util.Optional<String> body = get(baseUrl + path);
        if (body.isEmpty()) {
            unobservable.add(dimension.name() + ": " + path + " not exposed by " + moduleId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            JsonNode node = Json.parse(body.get());
            data.put("summary", summarize(dimension, node));
            data.put("raw_hash", com.bootshift.core.util.Hashing.sha256(body.get()));
        } catch (RuntimeException e) {
            data.put("raw_hash", com.bootshift.core.util.Hashing.sha256(body.get()));
        }
        observations.add(new Observation(dimension, moduleId, path, data, null, true));
    }

    private void collectConfigProps(String baseUrl, String moduleId, List<Observation> observations,
                                    List<BoundProperty> bound, List<String> unobservable) {
        java.util.Optional<String> body = get(baseUrl + "/actuator/configprops");
        if (body.isEmpty()) {
            unobservable.add(Dimension.CONFIG_BINDING.name()
                    + ": /actuator/configprops not exposed; bound-property provenance is unavailable "
                    + "for " + moduleId + ", so PROPERTY_SILENTLY_IGNORED cannot be confirmed at runtime");
            return;
        }
        JsonNode root = Json.parse(body.get());
        root.path("contexts").fields().forEachRemaining(context ->
                context.getValue().path("beans").fields().forEachRemaining(bean -> {
                    String prefix = bean.getValue().path("prefix").asText("");
                    JsonNode properties = bean.getValue().path("properties");
                    properties.fields().forEachRemaining(property -> {
                        String key = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
                        boolean sensitive = SensitiveValues.isSensitiveKey(key);
                        bound.add(new BoundProperty(key, null, "CONFIGURATION_PROPERTIES",
                                bean.getValue().path("prefix").asText(null), property.getKey(),
                                true, property.getValue().isNull(), false, null, sensitive));
                    });
                }));
        observations.add(new Observation(Dimension.CONFIG_BINDING, moduleId,
                "/actuator/configprops", Map.of("bound_property_count", bound.size()), null, true));
    }

    private String summarize(Dimension dimension, JsonNode node) {
        return switch (dimension) {
            case BEANS -> "contexts=" + node.path("contexts").size();
            case HEALTH -> "status=" + node.path("status").asText("UNKNOWN");
            case REQUEST_MAPPINGS -> "contexts=" + node.path("contexts").size();
            case CONDITIONS -> "contexts=" + node.path("contexts").size();
            default -> "fields=" + node.size();
        };
    }

    private String summarizeFailure(Path logFile, Process process) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return "no startup log produced (exit=" + exitCodeOf(process) + ")";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && i > lines.size() - 200; i--) {
                String line = lines.get(i);
                if (line.contains("APPLICATION FAILED TO START") || line.contains("Caused by:")
                        || line.contains("Error creating bean") || line.contains("Exception")) {
                    return SensitiveValues.redactLine(null, line.trim());
                }
            }
            return "context did not become ready within " + STARTUP_TIMEOUT
                    + " (exit=" + exitCodeOf(process) + ")";
        } catch (IOException e) {
            return "startup log unreadable: " + e.getMessage();
        }
    }

    private static String exitCodeOf(Process process) {
        try {
            return process.isAlive() ? "running" : String.valueOf(process.exitValue());
        } catch (IllegalThreadStateException e) {
            return "running";
        }
    }

    private static List<String> allDimensionNames(String reason) {
        List<String> result = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            result.add(dimension.name() + ": " + reason);
        }
        return result;
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 18080 + (int) (System.nanoTime() % 1000);
        }
    }

    /**
     * The health status the application reports, as an observation in its own right.
     *
     * <p>A migration that turns UP into DOWN has changed behaviour even if every endpoint still
     * answers, so the status belongs in the evidence rather than only in the readiness decision.
     */
    private String healthStatus(String baseUrl) {
        return get(baseUrl + "/actuator/health")
                .map(body -> {
                    try {
                        return Json.parse(body).path("status").asText("UNKNOWN");
                    } catch (RuntimeException e) {
                        return "UNPARSEABLE";
                    }
                })
                .orElse("ABSENT");
    }
}
