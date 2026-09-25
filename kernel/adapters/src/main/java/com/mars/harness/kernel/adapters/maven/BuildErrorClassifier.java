package com.mars.harness.kernel.adapters.maven;

import com.mars.harness.kernel.ports.build.BuildPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithful Java port of the migration reference's build-error parser and classifier
 * ({@code 04d-version-migration/scripts/lib/migration.js}: {@code ERROR_CATEGORIES},
 * {@code parseBuildErrors}, {@code buildOutcome}).
 *
 * <p>Categories are "by message shape only; they never name a library and never propose a fix".
 * The regexes, their order, the noise and continuation filters, the "cannot find symbol — symbol"
 * enrichment and the file:line:message de-duplication are all kept identical. A parity test
 * runs the original JavaScript on the recorded round logs and compares the results.
 */
public final class BuildErrorClassifier {

    /** One category: id, label and hint are the legacy strings, verbatim. */
    public record Category(String id, String label, String hint, Pattern test) {
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    public static final List<Category> CATEGORIES = List.of(
            new Category("dependency-resolution", "Dependency not resolvable",
                    "The coordinate, version or repository changed — check the reference pack for renamed or split artifacts.",
                    Pattern.compile("could not resolve dependencies|could not find artifact|failed to read artifact descriptor|dependencies could not be resolved|could not determine the dependencies", CI)),
            new Category("missing-package", "Package does not exist",
                    "A type moved to a new package or module. Look for a relocation entry in the reference pack.",
                    Pattern.compile("package [\\w.]+ does not exist|error: package .* does not exist", CI)),
            new Category("missing-symbol", "Cannot find symbol",
                    "A class, method or field was renamed or removed. Look for a replacement API in the reference pack.",
                    Pattern.compile("cannot find symbol", CI)),
            new Category("incompatible-types", "Incompatible types",
                    "A signature changed shape (often a builder or callback). Check the reference pack for the new call form.",
                    Pattern.compile("incompatible types|bad return type|argument mismatch", CI)),
            new Category("no-suitable-method", "No suitable method / wrong arguments",
                    "An overload was removed or its parameters changed.",
                    Pattern.compile("no suitable method found|method .* cannot be applied to given types|constructor .* cannot be applied", CI)),
            new Category("abstract-not-implemented", "Interface contract changed",
                    "An interface gained, moved or changed a method — implement the new contract, or the type it came from has moved too.",
                    Pattern.compile("is not abstract and does not override abstract method|does not override abstract method|does not override or implement a method from a supertype", CI)),
            new Category("removed-api", "Removed or inaccessible API",
                    "The API still exists in name but is no longer accessible from here.",
                    Pattern.compile("has private access|is not public|is not visible|is deprecated and marked for removal", CI)),
            new Category("annotation-error", "Annotation no longer valid",
                    "An annotation was removed, renamed or moved module.",
                    Pattern.compile("annotation type not applicable|cannot find symbol\\s+symbol:\\s+class \\w*(Bean|Test|Mock)", CI)),
            new Category("java-release", "Java release / toolchain mismatch",
                    "The JDK running the build does not match what the build declares.",
                    Pattern.compile("invalid target release|invalid source release|release version .* not supported|has been compiled by a more recent version|unsupported class file major version", CI)),
            new Category("plugin-failure", "Build plugin failed",
                    "A build plugin is too old for the new platform, or its configuration changed.",
                    Pattern.compile("failed to execute goal|plugin .* or one of its dependencies could not be resolved|execution .* of goal", CI)),
            new Category("environment", "Environment, not the code",
                    "The machine is missing something the test or build needs (a container runtime, a service, a network route). Compare against the same goal on the pre-migration build before blaming the upgrade.",
                    Pattern.compile("could not find a valid docker environment|connection refused|unknownhost|no such host|daemon is not running|failed to start container", CI)),
            new Category("test-failure", "Test failure",
                    "The code compiled but behaviour or test wiring changed.",
                    Pattern.compile("tests run:.*(failures|errors): [1-9]|there are test failures|but was:|<<< (failure|error)!|\\w+Test\\.\\w+:\\d+", CI)));

    private static final Pattern CONTINUATION = Pattern.compile(
            "^(symbol|location|required|found|reason|actual|expected|where [A-Z]\\b)\\s*:", CI);
    private static final Pattern LOG_NOISE = Pattern.compile(
            "^(COMPILATION ERROR|BUILD FAILURE|ERROR|Failures|Errors|Tests in error|Skipped)\\s*:?\\s*$|^-{5,}$|^-+>|^To see the full stack trace|^Re-run Maven|^For more information|^\\[Help \\d\\]|^After correcting the problems|^See .* for the individual test results|^See dump files", CI);
    private static final Pattern MAVEN_LINE = Pattern.compile("^\\[ERROR\\]\\s+(.+?\\.(?:java|kt)):\\[(\\d+),(\\d+)\\]\\s+(.+)$");
    private static final Pattern JAVAC_LINE = Pattern.compile("^(?:\\[ERROR\\]\\s+)?(.+?\\.(?:java|kt)):(\\d+):\\s*error:\\s*(.+)$");
    private static final Pattern GENERIC = Pattern.compile("^\\[ERROR\\]\\s*(.*)$");
    private static final Pattern SYMBOL = Pattern.compile("^symbol\\s*:", CI);
    private static final Pattern CANNOT_FIND = Pattern.compile("cannot find symbol", CI);
    private static final Pattern TESTS_RUN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)(?:,\\s*Skipped:\\s*(\\d+))?");
    private static final Pattern FAILING_TEST = Pattern.compile(
            "^\\[ERROR\\]\\s+(?:Failures|Errors)?:?\\s*([\\w.$]+\\.[\\w$]+(?::\\d+)?)\\s");

    private BuildErrorClassifier() {
    }

    public static String classify(String message) {
        for (Category category : CATEGORIES) {
            if (category.test().matcher(message).find()) {
                return category.id();
            }
        }
        return "other";
    }

    public static Category meta(String id) {
        return CATEGORIES.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElse(new Category("other", "Uncategorised", "Read the raw log — this shape was not recognised.", null));
    }

    private static final class Entry {
        String file;
        Integer line;
        Integer column;
        String message;
        final List<String> detail = new ArrayList<>();
    }

    /** Parses and classifies. {@code root}, when given, is stripped from file paths. */
    public static List<BuildPort.BuildError> parse(String output, String root) {
        List<Entry> collected = new ArrayList<>();
        Entry last = null;
        for (String line : String.valueOf(output == null ? "" : output).split("\\r?\\n", -1)) {
            Matcher maven = MAVEN_LINE.matcher(line);
            if (maven.matches()) {
                last = entry(normaliseFile(maven.group(1), root), Integer.parseInt(maven.group(2)),
                        Integer.parseInt(maven.group(3)), maven.group(4).trim());
                collected.add(last);
                continue;
            }
            Matcher javac = JAVAC_LINE.matcher(line);
            if (javac.matches()) {
                last = entry(normaliseFile(javac.group(1), root), Integer.parseInt(javac.group(2)), null,
                        javac.group(3).trim());
                collected.add(last);
                continue;
            }
            Matcher generic = GENERIC.matcher(line);
            if (!generic.matches()) {
                continue;
            }
            String message = generic.group(1).trim();
            if (message.isEmpty() || LOG_NOISE.matcher(message).find()) {
                continue;
            }
            if (CONTINUATION.matcher(message).find()) {
                if (last != null) {
                    last.detail.add(message.replaceAll("\\s+", " "));
                }
                continue;
            }
            last = entry(null, null, null, message);
            collected.add(last);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<BuildPort.BuildError> errors = new ArrayList<>();
        for (Entry e : collected) {
            String symbol = e.detail.stream().filter(d -> SYMBOL.matcher(d).find()).findFirst().orElse(null);
            String message = symbol != null && CANNOT_FIND.matcher(e.message).find()
                    ? e.message + " — " + symbol.replaceFirst("(?i)^symbol\\s*:\\s*", "")
                    : e.message;
            String key = (e.file == null ? "" : e.file) + ":" + (e.line == null ? "" : e.line) + ":" + message;
            if (!seen.add(key)) {
                continue;
            }
            String category = classify(message + " " + String.join(" ", e.detail));
            errors.add(new BuildPort.BuildError(e.file, e.line, e.column, category, message,
                    String.join(" | ", e.detail)));
        }
        return errors;
    }

    private static Entry entry(String file, Integer line, Integer column, String message) {
        Entry e = new Entry();
        e.file = file;
        e.line = line;
        e.column = column;
        e.message = message;
        return e;
    }

    static String normaliseFile(String file, String root) {
        String normalised = file.trim().replace('\\', '/').replaceFirst("^/([A-Za-z]:)", "$1");
        if (root != null) {
            String rootPath = root.replace('\\', '/').replaceAll("/$", "");
            if (normalised.toLowerCase(java.util.Locale.ROOT).startsWith(rootPath.toLowerCase(java.util.Locale.ROOT) + "/")) {
                normalised = normalised.substring(rootPath.length() + 1);
            }
        }
        return normalised;
    }

    /** {@code buildOutcome}, verbatim. */
    public static BuildPort.Outcome outcome(int exitCode, boolean timedOut, List<BuildPort.BuildError> errors) {
        if (timedOut) {
            return BuildPort.Outcome.TIMED_OUT;
        }
        if (exitCode == 0) {
            return BuildPort.Outcome.PASSED;
        }
        if (errors.stream().anyMatch(e -> "test-failure".equals(e.category()))) {
            return BuildPort.Outcome.TESTS_FAILED;
        }
        if (errors.stream().anyMatch(e -> "dependency-resolution".equals(e.category()))) {
            return BuildPort.Outcome.DEPENDENCY_FAILED;
        }
        return BuildPort.Outcome.COMPILE_FAILED;
    }

    public static Map<String, Integer> byCategory(List<BuildPort.BuildError> errors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        errors.forEach(e -> counts.merge(e.category(), 1, Integer::sum));
        return counts;
    }

    /** The last Maven "Tests run" summary, as the reference renderer reads it. */
    public static BuildPort.TestSummary testSummary(String output) {
        Matcher m = TESTS_RUN.matcher(output == null ? "" : output);
        BuildPort.TestSummary last = null;
        while (m.find()) {
            last = new BuildPort.TestSummary(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), m.group(4) == null ? 0 : Integer.parseInt(m.group(4)));
        }
        return last;
    }

    /** Failing test identifiers from surefire's summary section ("[ERROR]   Class.method:line ..."). */
    public static List<String> failingTests(String output) {
        List<String> tests = new ArrayList<>();
        for (String line : String.valueOf(output == null ? "" : output).split("\\r?\\n")) {
            Matcher m = FAILING_TEST.matcher(line);
            if (m.find() && m.group(1).matches(".*Test[s]?\\.[\\w$]+(:\\d+)?")) {
                String id = m.group(1).replaceAll(":\\d+$", "");
                if (!tests.contains(id)) {
                    tests.add(id);
                }
            }
        }
        return tests;
    }
}
