package com.mars.harness.kernel.ports.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs the project's own build tool (spec §27 {@code BuildPort}).
 *
 * <p>The build tool is authoritative. Errors are classified by message shape only, as the
 * reference migration workflow requires. A classification never names a library and never
 * proposes a fix.
 */
public interface BuildPort {

    /** Build goals, cheapest first, matching the reference workflow's intents. */
    enum Intent {
        COMPILE, TEST_COMPILE, TEST, PACKAGE, VERIFY, PACKAGE_SKIP_TESTS, DEPENDENCY_TREE
    }

    /** Round outcome vocabulary preserved from the migration reference ({@code buildOutcome}). */
    enum Outcome {
        PASSED("passed"), COMPILE_FAILED("compile-failed"), DEPENDENCY_FAILED("dependency-failed"),
        TESTS_FAILED("tests-failed"), TIMED_OUT("timed-out"), TOOL_UNAVAILABLE("tool-unavailable");

        private final String legacyId;

        Outcome(String legacyId) {
            this.legacyId = legacyId;
        }

        public String legacyId() {
            return legacyId;
        }
    }

    record BuildError(String file, Integer line, Integer column, String category, String message, String raw) {
    }

    record TestSummary(int run, int failures, int errors, int skipped) {
        public int failed() {
            return failures + errors;
        }
    }

    /**
     * @param failingTests test identifiers from the surefire summary, for pre-existing failure
     *                     comparison
     * @param logRef       the full log, stored as an artifact (logs are not evidence; the parsed
     *                     result is)
     */
    record BuildResult(Intent intent, Outcome outcome, int exitCode, long durationMs, String tool,
                       String toolVersion, String jdk, List<String> command, List<BuildError> errors,
                       Map<String, Integer> errorsByCategory, TestSummary tests, List<String> failingTests,
                       String logTail, Path logRef, String unavailableReason) {

        public BuildResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            errorsByCategory = errorsByCategory == null ? Map.of() : Map.copyOf(errorsByCategory);
            failingTests = failingTests == null ? List.of() : List.copyOf(failingTests);
            command = command == null ? List.of() : List.copyOf(command);
        }

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }
    }

    /** Whether a build tool is usable for this project, and why not when it isn't. */
    record Availability(boolean available, String tool, String version, String reason) {
    }

    Availability availability(Path projectDir);

    BuildResult build(Path projectDir, Intent intent, Path logFile);
}
