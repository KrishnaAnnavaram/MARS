package com.bootshift.adapters.environment;

import com.bootshift.adapters.exec.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provisions the infrastructure a runtime observation needs, using a rootless OCI runtime.
 *
 * <p>Without this, every dimension that needs a datastore or a broker was permanently
 * {@code NOT_COMPARED}: the provider detected whether a container binary existed on {@code PATH} and
 * then provisioned nothing, so persistence, query, transaction, messaging and batch behaviour were
 * never compared on any run.
 *
 * <p>Two honesty rules shape this class:
 *
 * <ul>
 *   <li><b>A binary on PATH is not a runtime.</b> Docker Desktop can be installed with its engine
 *       stopped, which is the everyday case on a developer machine. Availability is decided by asking
 *       the daemon, not by finding the executable.</li>
 *   <li><b>Both sides get the same thing or neither is compared.</b> OLD and NEW receive containers
 *       from the same pinned image digest with the same configuration. If provisioning succeeds for
 *       one side and fails for the other, the dimension is NOT_COMPARED rather than compared against
 *       a different environment.</li>
 * </ul>
 */
public final class ContainerProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerProvisioner.class);

    /** A component the harness knows how to run, pinned to an exact tag. */
    public record ImageSpec(String component, String image, String tag, int containerPort,
                            List<String> environment, String readyLogFragment, String endpointScheme) {

        public String reference() {
            return image + ":" + tag;
        }
    }

    /** One running container, with everything the evidence record requires. */
    public record Provisioned(String component, String containerId, String image, String tag,
                              String digest, int hostPort, String endpoint,
                              Map<String, String> configuration, String lifecycle, boolean usable,
                              String unusableReason) {
    }

    /**
     * Pinned images.
     *
     * <p>Tags rather than floating {@code latest}: a differential comparison whose two sides pulled
     * different builds of {@code latest} is comparing two environments, and the digest recorded per
     * run is what makes that checkable after the fact.
     */
    private static final Map<String, ImageSpec> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("postgresql", new ImageSpec("postgresql", "postgres", "16.4-alpine", 5432,
                List.of("POSTGRES_PASSWORD=bootshift", "POSTGRES_USER=bootshift",
                        "POSTGRES_DB=bootshift"),
                "database system is ready to accept connections", "jdbc:postgresql"));
        CATALOG.put("mysql", new ImageSpec("mysql", "mysql", "8.4", 3306,
                List.of("MYSQL_ROOT_PASSWORD=bootshift", "MYSQL_DATABASE=bootshift"),
                "ready for connections", "jdbc:mysql"));
        CATALOG.put("mariadb", new ImageSpec("mariadb", "mariadb", "11.4", 3306,
                List.of("MARIADB_ROOT_PASSWORD=bootshift", "MARIADB_DATABASE=bootshift"),
                "ready for connections", "jdbc:mariadb"));
        CATALOG.put("mongodb", new ImageSpec("mongodb", "mongo", "7.0", 27017,
                List.of(), "Waiting for connections", "mongodb"));
        CATALOG.put("redis", new ImageSpec("redis", "redis", "7.4-alpine", 6379,
                List.of(), "Ready to accept connections", "redis"));
        CATALOG.put("rabbitmq", new ImageSpec("rabbitmq", "rabbitmq", "3.13-alpine", 5672,
                List.of(), "Server startup complete", "amqp"));
        CATALOG.put("kafka", new ImageSpec("kafka", "apache/kafka", "3.8.0", 9092,
                List.of("KAFKA_NODE_ID=1", "KAFKA_PROCESS_ROLES=broker,controller",
                        "KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093",
                        "KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093",
                        "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
                        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1"),
                "Kafka Server started", "kafka"));
    }

    private final ProcessRunner runner;
    private final String runtime;
    private final List<String> started = new ArrayList<>();

    public ContainerProvisioner() {
        this(new ProcessRunner(Set.of("podman", "podman.exe", "docker", "docker.exe",
                "nerdctl", "nerdctl.exe"), 4000));
    }

    public ContainerProvisioner(ProcessRunner runner) {
        this.runner = runner;
        this.runtime = detectWorkingRuntime(runner);
    }

    /** The components this provisioner can supply. */
    public static Set<String> catalogComponents() {
        return CATALOG.keySet();
    }

    public static Optional<ImageSpec> specFor(String component) {
        return Optional.ofNullable(CATALOG.get(component == null ? null
                : component.toLowerCase(Locale.ROOT)));
    }

    /** The runtime actually answering, or null. */
    public String runtime() {
        return runtime;
    }

    public boolean available() {
        return runtime != null;
    }

    /**
     * Finds an OCI runtime whose <em>daemon</em> responds.
     *
     * <p>{@code which docker} succeeding proves the CLI is installed. On a machine where Docker
     * Desktop is installed but not started, every subsequent command fails, and a provider that
     * reported "container.runtime=docker" on that basis would be claiming an isolation mechanism it
     * does not have.
     */
    public static String detectWorkingRuntime(ProcessRunner runner) {
        for (String candidate : List.of("podman", "docker", "nerdctl")) {
            String resolved = ProcessRunner.which(candidate);
            if (resolved == null) {
                continue;
            }
            try {
                ProcessRunner.Result result = runner.run(
                        List.of(resolved, "version", "--format", "{{.Server.Version}}"),
                        Path.of(System.getProperty("user.dir")), Duration.ofSeconds(20), Map.of());
                if (result.success() && !result.stdoutText().isBlank()) {
                    return candidate;
                }
                LOG.info("{} is installed but its daemon did not answer; it is not usable as a "
                        + "container runtime for this run", candidate);
            } catch (RuntimeException e) {
                LOG.debug("Probing {} failed: {}", candidate, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Starts one component for one side of the migration.
     *
     * @param role the migration side, so OLD and NEW get distinct containers on distinct ports
     */
    public Provisioned provision(String component, String role) {
        Optional<ImageSpec> spec = specFor(component);
        if (spec.isEmpty()) {
            return unusable(component, "No pinned image is known for " + component
                    + ". The harness will not invent one: an unpinned image would make the two sides "
                    + "of a comparison potentially different environments.");
        }
        if (!available()) {
            return unusable(component, "No OCI runtime daemon answered. A container binary on PATH "
                    + "is not a runtime; the daemon has to be running.");
        }

        ImageSpec image = spec.get();
        String name = "bootshift-" + component + "-"
                + role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        int hostPort = freePort();

        List<String> command = new ArrayList<>();
        command.add(ProcessRunner.which(runtime));
        command.add("run");
        command.add("-d");
        command.add("--rm");
        command.add("--name");
        command.add(name);
        command.add("-p");
        command.add("127.0.0.1:" + hostPort + ":" + image.containerPort());
        image.environment().forEach(entry -> {
            command.add("-e");
            command.add(entry);
        });
        command.add(image.reference());

        ProcessRunner.Result result = runner.run(command, Path.of(System.getProperty("user.dir")),
                Duration.ofMinutes(5), Map.of());
        if (!result.success()) {
            return unusable(component, "Could not start " + image.reference() + ": "
                    + lastLine(result));
        }
        String containerId = result.stdoutText().trim();
        started.add(name);

        String digest = digestOf(image.reference());
        boolean ready = waitForReady(name, image);
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("image", image.reference());
        configuration.put("container_port", String.valueOf(image.containerPort()));
        configuration.put("host_port", String.valueOf(hostPort));
        configuration.put("environment", String.join(",", image.environment()));
        configuration.put("network", "127.0.0.1 only");
        configuration.put("runtime", runtime);

        String endpoint = image.endpointScheme() + "://127.0.0.1:" + hostPort;
        return new Provisioned(component, containerId, image.image(), image.tag(), digest, hostPort,
                endpoint, configuration, ready ? "RUNNING" : "STARTED_NOT_READY", ready,
                ready ? null : "The container started but did not report readiness within the "
                        + "timeout, so it cannot be used as an equivalent environment");
    }

    /** Waits for the component's own readiness line in its logs. */
    private boolean waitForReady(String name, ImageSpec image) {
        java.time.Instant deadline = java.time.Instant.now().plus(Duration.ofSeconds(120));
        while (java.time.Instant.now().isBefore(deadline)) {
            ProcessRunner.Result logs = runner.run(
                    List.of(ProcessRunner.which(runtime), "logs", name),
                    Path.of(System.getProperty("user.dir")), Duration.ofSeconds(30), Map.of());
            String text = logs.stdoutText() + System.lineSeparator() + logs.stderrText();
            if (image.readyLogFragment() == null || text.contains(image.readyLogFragment())) {
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** The image digest, so the evidence names exactly which bytes ran. */
    private String digestOf(String reference) {
        ProcessRunner.Result result = runner.run(
                List.of(ProcessRunner.which(runtime), "image", "inspect", reference,
                        "--format", "{{index .RepoDigests 0}}"),
                Path.of(System.getProperty("user.dir")), Duration.ofSeconds(30), Map.of());
        String digest = result.stdoutText().trim();
        return digest.isBlank() ? "unknown" : digest;
    }

    /** Stops everything this provisioner started. Deterministic cleanup, not best effort. */
    public List<String> release() {
        List<String> failures = new ArrayList<>();
        for (String name : new ArrayList<>(started)) {
            ProcessRunner.Result result = runner.run(
                    List.of(ProcessRunner.which(runtime), "rm", "-f", name),
                    Path.of(System.getProperty("user.dir")), Duration.ofSeconds(60), Map.of());
            if (!result.success()) {
                failures.add(name + ": " + lastLine(result));
            }
            started.remove(name);
        }
        return failures;
    }

    private static Provisioned unusable(String component, String reason) {
        return new Provisioned(component, null, null, null, null, 0, null, Map.of(),
                "NOT_PROVISIONED", false, reason);
    }

    private static String lastLine(ProcessRunner.Result result) {
        List<String> lines = new ArrayList<>(result.stderr());
        lines.addAll(result.stdout());
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i).trim();
            }
        }
        return "no output";
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return 20000 + (int) (System.nanoTime() % 5000);
        }
    }
}
