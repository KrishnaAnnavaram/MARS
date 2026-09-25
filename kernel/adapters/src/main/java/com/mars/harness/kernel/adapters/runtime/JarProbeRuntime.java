package com.mars.harness.kernel.adapters.runtime;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runtime probing, ported from the migration reference's {@code probe-runtime.js}.
 *
 * <ul>
 *   <li>The runnable jar is the largest {@code target/*.jar}, excluding {@code .original},
 *       {@code -sources}, {@code -javadoc} and {@code -plain}.</li>
 *   <li>The app starts as {@code java -jar <jar> --server.port=<port>} through Bootshift's
 *       ProcessRunner (allowlist, sanitised environment, tree termination).</li>
 *   <li>Readiness: the readiness path is polled every second. "Any HTTP answer proves the server
 *       is up; 401 counts."</li>
 *   <li>Each request is replayed with Basic auth unless {@code noAuth}, with a JSON content type
 *       when it has a body.</li>
 *   <li>{@code body_hash} = first 16 hex characters of the SHA-256 of the whitespace-normalised
 *       body; the excerpt is capped at 1500 characters.</li>
 * </ul>
 *
 * <p>Hardening beyond the reference: probes may only target loopback, so a probe file cannot turn
 * the harness into an egress channel.
 *
 * <p>Working directory: the reference starts the baseline and the final application from the same
 * project directory, so relative runtime state (an H2 file database such as
 * {@code jdbc:h2:file:./data/...}, uploads) carries over from one to the other. Each harness build
 * runs in a fresh {@code exec/} copy, so the application instead runs in one shared per-run
 * {@code exec/runtime-state} directory. Without it, data seeded at startup gets new timestamps on
 * every run and the comparison reports differences that are not the migration's.
 */
public final class JarProbeRuntime implements RuntimePort {

    private static final Pattern STARTED = Pattern.compile("Started \\S+ in ([0-9.]+) seconds");

    private final ProcessRunner runner = new ProcessRunner();
    private final String javaExecutable;

    public JarProbeRuntime() {
        this(ProcessRunner.jdkTool("java"));
    }

    public JarProbeRuntime(String javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    @Override
    public RuntimeRun run(Path projectDir, ProbeSpec spec, String phase, Path logFile) {
        String jdk = System.getProperty("java.version");
        Path jar = runnableJar(projectDir.resolve("target"));
        if (jar == null) {
            return new RuntimeRun(phase, false, "no runnable jar under target/ (package the project first)", null,
                    null, jdk, null, null, List.of(), "");
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            return new RuntimeRun(phase, false, "no free loopback port: " + e.getMessage(), null, jar.getFileName().toString(),
                    jdk, null, null, List.of(), "");
        }
        String base = "http://127.0.0.1:" + port;
        List<String> command = List.of(javaExecutable, "-jar", jar.toAbsolutePath().toString(), "--server.port=" + port);
        Path workingDir = projectDir.toAbsolutePath().normalize().resolveSibling("runtime-state");
        try {
            java.nio.file.Files.createDirectories(workingDir);
        } catch (IOException e) {
            return new RuntimeRun(phase, false, "cannot create the runtime working directory: " + e.getMessage(), null,
                    jar.getFileName().toString(), jdk, null, null, List.of(), "");
        }
        ProcessRunner.Handle handle = runner.start(command, workingDir, Map.of(), logFile);
        if (!handle.started()) {
            return new RuntimeRun(phase, false, "process did not start: " + handle.failure(), null,
                    jar.getFileName().toString(), jdk, null, null, List.of(), "");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            long started = System.currentTimeMillis();
            Integer readiness = null;
            long deadline = started + Math.max(10, spec.readinessTimeoutSeconds()) * 1000L;
            String readinessPath = spec.readinessPath() == null ? "/actuator/health" : spec.readinessPath();
            while (System.currentTimeMillis() < deadline && handle.process().isAlive()) {
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + readinessPath))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    readiness = response.statusCode();
                    break;
                } catch (IOException e) {
                    sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long readyAfter = System.currentTimeMillis() - started;
            if (readiness == null) {
                String tail = tail(logFile, 6000);
                return new RuntimeRun(phase, false, handle.process().isAlive()
                        ? "not ready within " + spec.readinessTimeoutSeconds() + "s"
                        : "process exited before becoming ready", null, jar.getFileName().toString(), jdk, null,
                        readyAfter, List.of(), tail);
            }
            List<ProbeObservation> observations = new ArrayList<>();
            for (ProbeRequest request : spec.requests()) {
                observations.add(probe(client, base, spec, request));
            }
            String tail = tail(logFile, 6000);
            return new RuntimeRun(phase, true, null, startupSeconds(tail), jar.getFileName().toString(), jdk, readiness,
                    readyAfter, observations, tail);
        } finally {
            handle.terminate(Duration.ofSeconds(10));
        }
    }

    private ProbeObservation probe(HttpClient client, String base, ProbeSpec spec, ProbeRequest request) {
        String path = request.path().startsWith("/") ? request.path() : "/" + request.path();
        URI uri = URI.create(base + path);
        if (!"127.0.0.1".equals(uri.getHost())) {
            return new ProbeObservation(request.name(), request.method(), path, !request.noAuth(), 0, false, null, null,
                    0, null, null, "refused: probes may only target loopback");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        String method = request.method() == null ? "GET" : request.method().toUpperCase(Locale.ROOT);
        if (request.body() != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(request.body()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        boolean authenticated = !request.noAuth() && spec.authUser() != null;
        if (authenticated) {
            String token = Base64.getEncoder().encodeToString((spec.authUser() + ":" + spec.authPassword())
                    .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        request.headers().forEach(builder::setHeader);
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            return new ProbeObservation(request.name(), method, path, authenticated, System.currentTimeMillis() - start,
                    true, response.statusCode(), response.headers().firstValue("content-type").orElse(null),
                    body.length(), hashBody(body), body.length() > 1500 ? body.substring(0, 1500) : body, null);
        } catch (IOException e) {
            return new ProbeObservation(request.name(), method, path, authenticated, System.currentTimeMillis() - start,
                    false, null, null, 0, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeObservation(request.name(), method, path, authenticated, 0, false, null, null, 0, null, null,
                    "interrupted");
        }
    }

    /** {@code hashBody}: sha256 of the whitespace-normalised body, first 16 hex characters. */
    public static String hashBody(String text) {
        return Hashing.sha256(String.valueOf(text == null ? "" : text).replaceAll("\\s+", " ").trim()).substring(0, 16);
    }

    static Path runnableJar(Path target) {
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.endsWith(".original") && !n.contains("-sources") && !n.contains("-javadoc")
                                && !n.contains("-plain");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Double startupSeconds(String log) {
        Matcher m = STARTED.matcher(log == null ? "" : log);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    private static String tail(Path file, int chars) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return "";
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String t = text.length() > chars ? text.substring(text.length() - chars) : text;
            return ProcessRunner.redact(t);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
