package com.bootshift.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.BuildSystemResolver;
import com.bootshift.adapters.build.JavaTargetSelector;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildSystemPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The toolchain an edge actually executes on, and the proof that it did.
 *
 * <p>The planner freezes a Java target per edge. Nothing downstream honoured it: the repair stage
 * re-derived a JDK from the edge's target version, the runtime stage hardcoded 17, and the runtime
 * probe resolved {@code java} from whatever was on PATH. So the plan could say Java 21 and the
 * compile could happen on Java 17, and no artifact recorded the difference.
 *
 * <p>This class resolves the frozen selection to a concrete JDK home, hands callers an explicit
 * executable path rather than a bare command name, and afterwards asks the process what version it
 * really was. A mismatch between the frozen target and the executed toolchain is a validation
 * failure, not a footnote: evidence produced on an unplanned JDK is evidence about a different
 * migration.
 */
public final class EdgeToolchain {

    /** What the edge plan froze, resolved against the machine. */
    public record Resolved(int frozenMajor, String javaHome, String javaExecutable, String version,
                           String vendor, String selectionReason, boolean available,
                           String unavailableReason) {

        public boolean usable() {
            return available && javaHome != null;
        }
    }

    /** What a process actually reported, compared against the frozen target. */
    public record Verification(boolean verified, int expectedMajor, Integer observedMajor,
                               String observedVersion, String detail) {
    }

    private final Resolved resolved;
    private final BuildSystemResolver builds;
    private final BuildSystemPort.BuildModel buildModel;

    private EdgeToolchain(Resolved resolved, BuildSystemResolver builds,
                          BuildSystemPort.BuildModel buildModel) {
        this.resolved = resolved;
        this.builds = builds;
        this.buildModel = buildModel;
    }

    /**
     * Resolves the toolchain frozen into an edge plan.
     *
     * <p>Reads {@code edge_java_home} first: the planner already selected a concrete JDK and
     * recorded it, so re-running the selection here could pick a different one if the machine
     * changed underneath. Falls back to re-selecting the frozen major when the home no longer exists,
     * and says so.
     */
    public static EdgeToolchain forEdge(JsonNode edgePlan, BuildSystemPort.BuildModel buildModel) {
        int frozenMajor = parseInt(edgePlan.path("edge_java").asText(null),
                parseInt(edgePlan.path("current_java").asText(null), 17));
        String plannedHome = edgePlan.path("edge_java_home").asText(null);
        String reason = edgePlan.path("edge_java_selection_reason")
                .asText("frozen by the migration plan");

        if (plannedHome != null && !plannedHome.isBlank() && Files.isDirectory(Path.of(plannedHome))) {
            Optional<Path> executable = JavaTargetSelector.javaExecutable(plannedHome);
            return new EdgeToolchain(new Resolved(frozenMajor, plannedHome,
                    executable.map(Path::toString).orElse(null),
                    edgePlan.path("edge_java_version").asText(null),
                    edgePlan.path("edge_java_vendor").asText(null),
                    reason, executable.isPresent(),
                    executable.isPresent() ? null
                            : "The frozen JDK home exists but contains no java executable"),
                    new BuildSystemResolver(), buildModel);
        }

        // The frozen home is gone. Re-select the same major rather than silently using another.
        ToolchainProbe probe = new ToolchainProbe();
        Optional<ToolchainProbe.Jdk> jdk = probe.discover().stream()
                .filter(candidate -> candidate.major() == frozenMajor)
                .findFirst();
        if (jdk.isEmpty()) {
            return new EdgeToolchain(new Resolved(frozenMajor, null, null, null, null, reason, false,
                    "No installed JDK provides Java " + frozenMajor + ", which this edge froze. "
                            + "Compiling or running on a different major would produce evidence about "
                            + "a toolchain the plan did not select."),
                    new BuildSystemResolver(), buildModel);
        }
        String home = jdk.get().home().toString();
        Optional<Path> executable = JavaTargetSelector.javaExecutable(home);
        return new EdgeToolchain(new Resolved(frozenMajor, home,
                executable.map(Path::toString).orElse(null), jdk.get().version(), jdk.get().vendor(),
                reason + " (re-resolved: the planned home was no longer present)",
                executable.isPresent(), null),
                new BuildSystemResolver(), buildModel);
    }

    public Resolved resolved() {
        return resolved;
    }

    public BuildSystemResolver builds() {
        return builds;
    }

    /** The build provider that owns a module, from the resolved build model. */
    public BuildSystemPort providerFor(BuildSystemPort.ModuleModel module, Path moduleRoot) {
        return builds.providerFor(module, moduleRoot).orElse(builds.maven());
    }

    /**
     * Build options carrying the frozen toolchain.
     *
     * <p>{@code bootshift.javaHome} is consumed by the build adapters, which set JAVA_HOME and
     * prepend its bin directory to PATH for the child process. That is what makes the selection take
     * effect rather than remain a number in an artifact.
     */
    public Map<String, String> buildOptions(Path logSink) {
        Map<String, String> options = new LinkedHashMap<>();
        if (logSink != null) {
            options.put("bootshift.logSink", logSink.toString());
        }
        if (resolved.javaHome() != null) {
            options.put("bootshift.javaHome", resolved.javaHome());
        }
        if (resolved.javaExecutable() != null) {
            options.put("bootshift.javaExecutable", resolved.javaExecutable());
        }
        return options;
    }

    /**
     * Asks the selected JDK what it is, and compares that against the frozen target.
     *
     * <p>Recording the intended version is not evidence that it was used. This executes the exact
     * binary the edge will build with and reads the version it prints.
     */
    public Verification verify() {
        if (!resolved.usable()) {
            return new Verification(false, resolved.frozenMajor(), null, null,
                    resolved.unavailableReason() == null
                            ? "No JDK was resolved for this edge" : resolved.unavailableReason());
        }
        ProcessRunner.Result result = new ProcessRunner().run(
                List.of(resolved.javaExecutable(), "-version"),
                Path.of(resolved.javaHome()), Duration.ofSeconds(60), Map.of());
        List<String> lines = new ArrayList<>(result.stderr());
        lines.addAll(result.stdout());
        String observedVersion = null;
        for (String line : lines) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:openjdk|java) version \"([0-9._]+)")
                    .matcher(line);
            if (matcher.find()) {
                observedVersion = matcher.group(1);
                break;
            }
        }
        if (observedVersion == null) {
            return new Verification(false, resolved.frozenMajor(), null, null,
                    "The selected java executable did not report a version: "
                            + String.join(" | ", lines.subList(0, Math.min(3, lines.size()))));
        }
        int observedMajor = ToolchainProbe.majorOf(observedVersion);
        boolean matches = observedMajor == resolved.frozenMajor();
        return new Verification(matches, resolved.frozenMajor(), observedMajor, observedVersion,
                matches ? "The executed toolchain is the one the edge froze"
                        : "TOOLCHAIN_MISMATCH: the edge froze Java " + resolved.frozenMajor()
                                + " but " + resolved.javaExecutable() + " reports "
                                + observedVersion + ". Evidence produced here describes a different "
                                + "toolchain than the plan authorized.");
    }

    /** The toolchain record that goes into a stage artifact. */
    public ObjectNode toNode(Verification verification) {
        ObjectNode node = Json.obj();
        node.put("frozen_java_major", resolved.frozenMajor());
        node.put("java_home", normalize(resolved.javaHome()));
        node.put("java_executable", normalize(resolved.javaExecutable()));
        node.put("java_version_planned", resolved.version());
        node.put("java_vendor", resolved.vendor());
        node.put("selection_reason", resolved.selectionReason());
        node.put("available", resolved.usable());
        node.put("unavailable_reason", resolved.unavailableReason());
        node.put("executed_version_verified", verification.verified());
        node.put("executed_java_version", verification.observedVersion());
        node.put("executed_java_major", verification.observedMajor());
        node.put("verification_detail", verification.detail());
        node.put("build_kind", buildModel == null ? "UNKNOWN" : buildModel.kind().name());
        node.put("build_tool_version", buildModel == null ? null : buildModel.toolVersion());
        node.put("build_tool_invocation", buildModel == null ? null : buildModel.toolInvocation());
        node.put("build_wrapper_used", buildModel != null && buildModel.wrapperUsed());
        node.put("rule", "Compile, test, package and run all execute on the JDK this edge froze. A "
                + "mismatch between the frozen target and the executed toolchain is a validation "
                + "failure, because the observation would describe a toolchain nobody selected.");
        return node;
    }

    private static String normalize(String path) {
        return path == null ? null : path.replace((char) 92, '/');
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
