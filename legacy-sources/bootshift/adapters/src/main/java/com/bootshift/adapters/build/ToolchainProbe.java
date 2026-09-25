package com.bootshift.adapters.build;

import com.bootshift.adapters.exec.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the JDKs available on this machine.
 *
 * <p>The original application must be built and observed on a JDK it actually supports. Compiling a
 * Spring Boot 2.7 project on JDK 21 does not test the migration - it tests whether an old annotation
 * processor happens to survive a newer javac, and it produces a baseline failure that has nothing to
 * do with the migration.
 *
 * <p>Selecting a matching toolchain is environment provisioning, not mutation: the application is
 * never modified. When no compatible JDK exists the probe says so, and the baseline records a
 * toolchain blind spot rather than pretending the build outcome was meaningful.
 */
public final class ToolchainProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ToolchainProbe.class);

    /** One discovered JDK. */
    public record Jdk(int major, String version, String vendor, Path home, String discoveredBy) {
    }

    private static final Pattern VERSION_LINE =
            Pattern.compile("(?:openjdk|java) version \"([0-9._]+)");

    private final ProcessRunner runner;
    private final Map<Path, Jdk> cache = new LinkedHashMap<>();

    public ToolchainProbe() {
        this(new ProcessRunner());
    }

    public ToolchainProbe(ProcessRunner runner) {
        this.runner = runner;
    }

    /** All JDKs the harness can see, newest first. */
    public List<Jdk> discover() {
        List<Jdk> found = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();

        // 1. Explicit harness overrides win: BOOTSHIFT_JDK_17, BOOTSHIFT_JDK_21, ...
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("BOOTSHIFT_JDK_") && value != null && !value.isBlank()) {
                candidates.add(Path.of(value));
            }
        });
        // 2. The JDK running the harness.
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            candidates.add(Path.of(javaHome));
        }
        // 3. JAVA_HOME.
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isBlank()) {
            candidates.add(Path.of(envHome));
        }
        // 4. Conventional install roots.
        for (String root : List.of(
                System.getProperty("user.home") + "/tools",
                System.getProperty("user.home") + "/.jdks",
                System.getProperty("user.home") + "/.sdkman/candidates/java",
                "C:/Program Files/Java",
                "C:/Program Files/Microsoft",
                "C:/Program Files/Eclipse Adoptium",
                "C:/Program Files/Amazon Corretto",
                "C:/Program Files/Zulu",
                "/usr/lib/jvm",
                "/Library/Java/JavaVirtualMachines")) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).forEach(p -> {
                    candidates.add(p);
                    // macOS layout
                    Path macOs = p.resolve("Contents/Home");
                    if (Files.isDirectory(macOs)) {
                        candidates.add(macOs);
                    }
                });
            } catch (IOException e) {
                LOG.debug("Cannot list {}: {}", dir, e.getMessage());
            }
        }

        for (Path candidate : candidates.stream().distinct().toList()) {
            probe(candidate).ifPresent(jdk -> {
                if (found.stream().noneMatch(existing -> existing.home().equals(jdk.home()))) {
                    found.add(jdk);
                }
            });
        }
        found.sort(java.util.Comparator.comparingInt(Jdk::major).reversed());
        return found;
    }

    /** Verifies a directory is a JDK by asking it for its version. */
    public Optional<Jdk> probe(Path home) {
        Jdk cached = cache.get(home);
        if (cached != null) {
            return Optional.of(cached);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = home.resolve("bin").resolve(windows ? "java.exe" : "java");
        Path javac = home.resolve("bin").resolve(windows ? "javac.exe" : "javac");
        if (!Files.isRegularFile(java) || !Files.isRegularFile(javac)) {
            return Optional.empty();
        }
        ProcessRunner.Result result = runner.run(List.of(java.toString(), "-version"),
                home, Duration.ofSeconds(45), Map.of());
        List<String> lines = new ArrayList<>(result.stderr());
        lines.addAll(result.stdout());
        String version = null;
        String vendor = "unknown";
        for (String line : lines) {
            Matcher matcher = VERSION_LINE.matcher(line);
            if (matcher.find()) {
                version = matcher.group(1);
            }
            if (line.contains("Runtime Environment")) {
                vendor = line.trim();
            }
        }
        if (version == null) {
            return Optional.empty();
        }
        Jdk jdk = new Jdk(majorOf(version), version, vendor, home.toAbsolutePath().normalize(),
                "filesystem-probe");
        cache.put(home, jdk);
        return Optional.of(jdk);
    }

    /**
     * Selects a JDK for a required language level.
     *
     * <p>Prefers an exact major match, because that is what the application was written and tested
     * against. Falls back to the closest newer JDK, and returns empty when nothing is compatible.
     */
    public Optional<Jdk> select(int requiredMajor, List<Jdk> available) {
        Optional<Jdk> exact = available.stream().filter(j -> j.major() == requiredMajor).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return available.stream()
                .filter(j -> j.major() > requiredMajor)
                .min(java.util.Comparator.comparingInt(Jdk::major));
    }

    /**
     * Known toolchain hazards that would make a baseline build fail for reasons unrelated to the
     * migration. Reported so the operator sees a diagnosis, not a stack trace.
     */
    public static Optional<String> knownHazard(int jdkMajor, String lombokVersion) {
        if (lombokVersion == null) {
            return Optional.empty();
        }
        if (jdkMajor >= 21 && compareLombok(lombokVersion, "1.18.30") < 0) {
            return Optional.of("Lombok " + lombokVersion + " predates JDK 21 support and fails with "
                    + "NoSuchFieldError on JCTree$JCImport.qualid. Build the baseline on JDK "
                    + "17 or 20, or note that the migration must also raise the Lombok version.");
        }
        if (jdkMajor >= 17 && compareLombok(lombokVersion, "1.18.22") < 0) {
            return Optional.of("Lombok " + lombokVersion + " predates JDK 17 support.");
        }
        return Optional.empty();
    }

    private static int compareLombok(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? safeInt(a[i]) : 0;
            int y = i < b.length ? safeInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int safeInt(String token) {
        try {
            return Integer.parseInt(token.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String cleaned = version.startsWith("1.") ? version.substring(2) : version;
        return safeInt(cleaned.split("[._]")[0]);
    }
}
