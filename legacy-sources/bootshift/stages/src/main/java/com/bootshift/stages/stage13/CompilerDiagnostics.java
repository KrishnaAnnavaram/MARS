package com.bootshift.stages.stage13;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiler diagnostic parsing and root-cause clustering (spec section 28).
 *
 * <p>The ordering is deliberate and matters more than the parsing: a dependency resolution failure
 * produces dozens of "cannot find symbol" errors, and repairing those individually is how a repair
 * loop burns its budget on symptoms. Root causes are diagnosed in the spec's order and errors are
 * attributed to the first cause that explains them.
 */
public final class CompilerDiagnostics {

    /** Root cause classes, in diagnosis order. Lower ordinal is diagnosed first. */
    public enum RootCause {
        DEPENDENCY_RESOLUTION,
        PLUGIN_OR_TOOLCHAIN,
        MISSING_TYPE_OR_PACKAGE,
        REMOVED_OR_RENAMED_API,
        GENERIC_OR_TYPE_MISMATCH,
        NAMESPACE_MIGRATION,
        APPLICATION_SPECIFIC
    }

    public record Diagnostic(String file, Integer line, Integer column, String severity,
                             String message, String raw) {
    }

    public record Cluster(RootCause rootCause, String signature, List<Diagnostic> diagnostics,
                          String explanation, String suggestedRule) {

        public int size() {
            return diagnostics.size();
        }
    }

    private static final Pattern JAVAC_ERROR = Pattern.compile(
            "^\\[(ERROR|WARNING)\\]\\s+(.+?):\\[(\\d+),(\\d+)\\]\\s+(.*)$");
    private static final Pattern MAVEN_ERROR = Pattern.compile("^\\[ERROR\\]\\s+(.*)$");

    private static final Pattern SYMBOL_DETAIL =
            Pattern.compile("symbol\\s*:\\s+(\\w+)\\s+([\\w.$<>\\[\\]]+)");
    private static final Pattern PACKAGE_MISSING =
            Pattern.compile("package\\s+([\\w.]+)\\s+does not exist");
    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile("cannot find symbol");
    private static final Pattern INCOMPATIBLE_TYPES = Pattern.compile("incompatible types");
    private static final Pattern METHOD_NOT_APPLICABLE =
            Pattern.compile("(?:no suitable method|method .* cannot be applied|is not applicable)");
    private static final Pattern RESOLUTION_FAILURE = Pattern.compile(
            "(?:Could not resolve dependencies|Failed to collect dependencies|"
                    + "Non-resolvable|Could not find artifact|Could not transfer artifact)");
    private static final Pattern PLUGIN_FAILURE = Pattern.compile(
            "(?:Failed to execute goal|No compiler is provided|invalid target release|"
                    + "Fatal error compiling|source option .* is no longer supported|"
                    + "release version .* not supported|NoSuchFieldError|UnsupportedClassVersionError)");

    /**
     * Maven wraps ordinary compile errors in a "Failed to execute goal ... Compilation failure"
     * line. That line is a summary of the diagnostics below it, not a toolchain fault, and
     * treating it as one tells the repair loop that no source edit can help - which is false and
     * ends the loop after a single round.
     */
    private static final Pattern COMPILATION_WRAPPER =
            Pattern.compile("(?i)Compilation (?:failure|error)");

    /** javac continuation lines belong to the diagnostic above them, not to a new one. */
    private static final Pattern JAVAC_CONTINUATION =
            Pattern.compile("^\\s*(?:symbol|location|required|found|reason|where)\\s*:.*$");

    /** Maven renders Windows paths as /C:/... . Restore a path a FILE_ID can be matched to. */
    static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String value = path.replace('\\', '/');
        if (value.length() > 2 && value.charAt(0) == '/' && value.charAt(2) == ':') {
            value = value.substring(1);
        }
        return value;
    }

    private CompilerDiagnostics() {
    }

    /** Parses Maven output into structured diagnostics. */
    public static List<Diagnostic> parse(List<String> lines) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.stripTrailing();
            Matcher javac = JAVAC_ERROR.matcher(line);
            if (javac.matches()) {
                diagnostics.add(new Diagnostic(normalizePath(javac.group(2)),
                        Integer.parseInt(javac.group(3)), Integer.parseInt(javac.group(4)),
                        javac.group(1), javac.group(5), line));
                continue;
            }

            // javac emits a diagnostic across several lines:
            //     [ERROR] Foo.java:[6,54] cannot find symbol
            //       symbol:   class EnableEurekaClient
            //       location: package org.springframework.cloud.netflix.eureka
            // Recorded as three diagnostics, the symbol name never reaches the cluster (every
            // removed API becomes "unknown") and the two continuation lines land in
            // APPLICATION_SPECIFIC, inflating the residual the repair budget is sized against.
            String continuation = stripMavenPrefix(line);
            if (continuation != null && JAVAC_CONTINUATION.matcher(continuation).matches()
                    && !diagnostics.isEmpty()) {
                Diagnostic previous = diagnostics.remove(diagnostics.size() - 1);
                diagnostics.add(new Diagnostic(previous.file(), previous.line(),
                        previous.column(), previous.severity(),
                        previous.message() + " | " + continuation.strip(),
                        previous.raw() + System.lineSeparator() + line));
                continue;
            }

            Matcher maven = MAVEN_ERROR.matcher(line);
            if (maven.matches() && !maven.group(1).isBlank() && !isFraming(maven.group(1))) {
                diagnostics.add(new Diagnostic(null, null, null, "ERROR", maven.group(1), line));
            }
        }
        return diagnostics;
    }

    /**
     * Maven's framing around a failure: banners, help pointers, and the aggregate line that
     * summarizes the diagnostics printed above it.
     *
     * <p>None of these is a diagnostic and none is repairable, but each was being recorded as one and
     * classified APPLICATION_SPECIFIC - "no framework-level cause explains this". On the reference
     * corpus that turned a residual of 16 real errors into 32, and the repair budget is sized against
     * that number.
     */
    private static boolean isFraming(String message) {
        String trimmed = message.strip();
        if (trimmed.isEmpty() || trimmed.equals("-> [Help 1]") || trimmed.startsWith("-> [Help")) {
            return true;
        }
        if (trimmed.matches("(?i)COMPILATION ERROR\\s*:?") || trimmed.matches("(?i)BUILD FAILURE")) {
            return true;
        }
        if (trimmed.matches("^-{5,}$") || trimmed.matches("^\\[Help \\d+\\].*")) {
            return true;
        }
        // "Failed to execute goal ... : Compilation failure: Compilation failure:" summarizes the
        // per-file diagnostics that precede it; the diagnostics themselves are already recorded.
        if (trimmed.startsWith("Failed to execute goal")
                && COMPILATION_WRAPPER.matcher(trimmed).find()) {
            return true;
        }
        for (String prefix : List.of("Help", "To see", "Re-run", "For more", "After correcting",
                "mvn <args>", "Please read the following", "For more information about the errors")) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String stripMavenPrefix(String line) {
        Matcher maven = MAVEN_ERROR.matcher(line);
        if (maven.matches()) {
            return maven.group(1);
        }
        return line.isBlank() ? null : line;
    }

    /**
     * Groups diagnostics by root cause.
     *
     * <p>Attribution is first-match in diagnosis order, so a wave of missing-symbol errors caused by
     * an unresolved dependency is attributed to the dependency, not to each symbol.
     */
    public static List<Cluster> cluster(List<Diagnostic> diagnostics) {
        Map<String, List<Diagnostic>> buckets = new LinkedHashMap<>();
        Map<String, RootCause> causes = new LinkedHashMap<>();
        Map<String, String> explanations = new LinkedHashMap<>();
        Map<String, String> rules = new LinkedHashMap<>();

        boolean resolutionFailurePresent = diagnostics.stream()
                .anyMatch(d -> RESOLUTION_FAILURE.matcher(d.message()).find());

        for (Diagnostic diagnostic : diagnostics) {
            String message = diagnostic.message();

            if (RESOLUTION_FAILURE.matcher(message).find()) {
                record(buckets, causes, explanations, rules, "dependency-resolution",
                        RootCause.DEPENDENCY_RESOLUTION, diagnostic,
                        "The build could not resolve one or more artifacts. Every downstream missing "
                                + "symbol is a consequence of this, not an independent defect.",
                        "Fix the coordinate or the repository before repairing any symbol error.");
                continue;
            }
            if (PLUGIN_FAILURE.matcher(message).find()
                    && !COMPILATION_WRAPPER.matcher(message).find()) {
                record(buckets, causes, explanations, rules, "plugin-or-toolchain",
                        RootCause.PLUGIN_OR_TOOLCHAIN, diagnostic,
                        "A build plugin or the toolchain itself failed. This is an environment "
                                + "problem and no source edit can repair it.",
                        "Align the plugin version or the JDK with the target state.");
                continue;
            }

            Matcher packageMissing = PACKAGE_MISSING.matcher(message);
            if (packageMissing.find()) {
                String pkg = packageMissing.group(1);
                if (pkg.startsWith("javax.")) {
                    record(buckets, causes, explanations, rules, "namespace:" + pkg,
                            RootCause.NAMESPACE_MIGRATION, diagnostic,
                            "Package " + pkg + " no longer exists at the target version because it "
                                    + "relocated to the jakarta namespace.",
                            "jakarta.namespace");
                } else if (resolutionFailurePresent) {
                    record(buckets, causes, explanations, rules, "dependency-resolution",
                            RootCause.DEPENDENCY_RESOLUTION, diagnostic,
                            "Missing package attributed to the unresolved dependency above.",
                            "Fix dependency resolution first.");
                } else {
                    record(buckets, causes, explanations, rules, "missing-package:" + pkg,
                            RootCause.MISSING_TYPE_OR_PACKAGE, diagnostic,
                            "Package " + pkg + " is not on the compile classpath at the target version.",
                            "Add or restore the dependency that provides " + pkg + ".");
                }
                continue;
            }

            if (CANNOT_FIND_SYMBOL.matcher(message).find()) {
                Matcher symbol = SYMBOL_DETAIL.matcher(message);
                String subject = symbol.find() ? symbol.group(2) : "unknown";
                if (resolutionFailurePresent) {
                    record(buckets, causes, explanations, rules, "dependency-resolution",
                            RootCause.DEPENDENCY_RESOLUTION, diagnostic,
                            "Missing symbol attributed to the unresolved dependency above.",
                            "Fix dependency resolution first.");
                } else {
                    record(buckets, causes, explanations, rules, "removed-api:" + subject,
                            RootCause.REMOVED_OR_RENAMED_API, diagnostic,
                            "Symbol " + subject + " does not exist at the target version.",
                            "Look up the replacement in the verified migration knowledge for this edge.");
                }
                continue;
            }

            if (INCOMPATIBLE_TYPES.matcher(message).find()
                    || METHOD_NOT_APPLICABLE.matcher(message).find()) {
                record(buckets, causes, explanations, rules, "type-mismatch",
                        RootCause.GENERIC_OR_TYPE_MISMATCH, diagnostic,
                        "A signature changed at the target version, so an existing call no longer "
                                + "type-checks.",
                        "Adapt the call site to the new signature recorded in migration knowledge.");
                continue;
            }

            record(buckets, causes, explanations, rules,
                    "application:" + (diagnostic.file() == null ? "build" : diagnostic.file()),
                    RootCause.APPLICATION_SPECIFIC, diagnostic,
                    "No framework-level cause explains this diagnostic.",
                    "Requires targeted analysis; this is the residual the repair budget exists for.");
        }

        List<Cluster> clusters = new ArrayList<>();
        buckets.forEach((signature, list) -> clusters.add(new Cluster(causes.get(signature), signature,
                list, explanations.get(signature), rules.get(signature))));
        clusters.sort(java.util.Comparator
                .comparingInt((Cluster c) -> c.rootCause().ordinal())
                .thenComparing(java.util.Comparator.comparingInt(Cluster::size).reversed()));
        return clusters;
    }

    private static void record(Map<String, List<Diagnostic>> buckets, Map<String, RootCause> causes,
                               Map<String, String> explanations, Map<String, String> rules,
                               String signature, RootCause cause, Diagnostic diagnostic,
                               String explanation, String rule) {
        buckets.computeIfAbsent(signature, k -> new ArrayList<>()).add(diagnostic);
        causes.putIfAbsent(signature, cause);
        explanations.putIfAbsent(signature, explanation);
        rules.putIfAbsent(signature, rule);
    }

    /** True when no source edit can fix the cluster, so the repair loop must not try. */
    public static boolean isEnvironmental(RootCause cause) {
        return cause == RootCause.DEPENDENCY_RESOLUTION || cause == RootCause.PLUGIN_OR_TOOLCHAIN;
    }
}
