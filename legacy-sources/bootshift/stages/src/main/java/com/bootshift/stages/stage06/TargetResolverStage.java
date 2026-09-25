package com.bootshift.stages.stage06;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.JavaTargetSelector;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 06 - Target Resolver (spec section 17).
 *
 * <p>Decides <em>where</em> the application should go, never how to get there. The distinction that
 * matters is transit checkpoint versus landing target: 3.0 may be a legal step even though it is an
 * unacceptable place to stop.
 *
 * <p>{@code --target auto} means the highest <em>safe supported</em> stable GA state (R25). Ranking
 * is by supportability and compatibility evidence, not by version number, and every eliminated
 * candidate keeps its elimination reason so the decision is auditable.
 */
public final class TargetResolverStage implements Stage {

    public static final String OUTPUT_DIR = "06-target";

    /**
     * One evaluated candidate landing state.
     *
     * <p>{@code eliminations} disqualify a candidate outright. {@code cautions} do not: they are
     * things the harness believes but cannot prove to the standard required to reject a target, and
     * each one becomes an approval gate instead of a silent rejection.
     */
    public record Candidate(String line, String version, boolean stableGa, boolean eol,
                            long supportHorizonMonths, boolean artifactAvailable,
                            String springFrameworkLine, String springCloudTrain,
                            List<Integer> supportedJavaMajors, String lifecycleEvidenceQuality,
                            List<String> eliminations, List<String> cautions, double score) {

        public boolean viable() {
            return eliminations.isEmpty();
        }
    }

    private final String requestedTarget;

    public TargetResolverStage() {
        this("auto");
    }

    public TargetResolverStage(String requestedTarget) {
        this.requestedTarget = requestedTarget == null ? "auto" : requestedTarget;
    }

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Choose the landing target and the transit checkpoints that reach it";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.COMPATIBILITY_REGISTRY_READY);
    }

    @Override
    public RunState postcondition() {
        return RunState.TARGET_FROZEN;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("05-compatibility/lifecycle-registry.json",
                "05-compatibility/compatibility-registry.json",
                "05-compatibility/artifact-availability.json",
                "05-compatibility/internal-components.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("target-state.json", "migration-path.json", "target-resolution-report.json",
                "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode lifecycle = StageSupport.requireUpstream(context, "05-compatibility",
                "lifecycle-registry.json", "Run: harness compatibility");
        JsonNode compatibility = StageSupport.requireUpstream(context, "05-compatibility",
                "compatibility-registry.json", "Run: harness compatibility");
        JsonNode availability = StageSupport.requireUpstream(context, "05-compatibility",
                "artifact-availability.json", "Run: harness compatibility");
        JsonNode internal = StageSupport.requireUpstream(context, "05-compatibility",
                "internal-components.json", "Run: harness compatibility");

        String currentVersion = compatibility.path("current_spring_boot").asText(null);
        String currentJava = compatibility.path("current_java").asText("17");
        int runningJdk = compatibility.path("running_jdk_major").asInt(Runtime.version().feature());
        if (currentVersion == null || currentVersion.isBlank()) {
            throw HarnessException.refusal(
                    "The current Spring Boot version could not be determined from the build model; "
                            + "target resolution cannot proceed without a source state.");
        }

        Map<String, Boolean> artifactAvailable = new LinkedHashMap<>();
        availability.path("candidates").forEach(node ->
                artifactAvailable.put(node.path("line").asText(), node.path("exists").asBoolean(false)));

        int unknownInternal = internal.path("unknown_count").asInt();
        String internalAction = internal.path("policy_action").asText("BLOCK");

        // Judge Java constraints against the JDKs actually installed, not the one running the
        // harness. The harness JVM and the application toolchain are separate concerns, and deriving
        // one from the other is how a migration silently acquires a compiler-target change nobody
        // asked for.
        List<ToolchainProbe.Jdk> installedJdks = new ToolchainProbe().discover();
        List<Integer> availableJdks = new ArrayList<>(installedJdks.stream()
                .map(ToolchainProbe.Jdk::major).distinct().sorted().toList());
        if (availableJdks.isEmpty()) {
            // Nothing discovered is a fact worth recording, not a reason to substitute the harness
            // JVM: an application toolchain that does not exist cannot build anything.
            compatibility.path("available_jdk_majors").forEach(n -> availableJdks.add(n.asInt()));
        }

        boolean usesSpringCloud = compatibility.path("application_uses_spring_cloud").asBoolean(false);

        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode release : lifecycle.path("releases")) {
            candidates.add(evaluate(context, release, currentVersion, currentJava, availableJdks,
                    artifactAvailable, unknownInternal, internalAction, usesSpringCloud));
        }

        Candidate landing;
        String selectionMode;
        if ("auto".equalsIgnoreCase(requestedTarget)) {
            selectionMode = "AUTO_HIGHEST_SAFE_SUPPORTED";
            landing = candidates.stream()
                    .filter(Candidate::viable)
                    .max(java.util.Comparator.comparingDouble(Candidate::score))
                    .orElse(null);
        } else {
            selectionMode = "EXPLICIT";
            String requestedLine = lineOf(requestedTarget);
            landing = candidates.stream()
                    .filter(c -> c.line().equals(requestedLine) || c.version().equals(requestedTarget))
                    .findFirst()
                    .orElse(null);
            if (landing == null) {
                throw HarnessException.refusal("Requested target " + requestedTarget
                        + " is not a known Spring Boot release line. Known lines: "
                        + candidates.stream().map(Candidate::line).toList());
            }
            if (!landing.viable()) {
                throw HarnessException.block("Requested target " + requestedTarget
                        + " is not a safe landing state: " + landing.eliminations());
            }
        }

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR)
                .stat("candidates_evaluated", candidates.size())
                .stat("selection_mode", selectionMode);

        if (landing == null) {
            ObjectNode report = renderReport(currentVersion, currentJava, candidates, null,
                    selectionMode, List.of());
            writer.write("target-resolution-report.json",
                    StageSupport.compose(envelope, report));
            StageSupport.publish(context, writer);
            throw HarnessException.block(buildNoViableTargetExplanation(context, candidates));
        }

        List<Checkpoint> path = buildPath(context, currentVersion, currentJava, landing, candidates,
                installedJdks);
        Checkpoint landingEdge = path.stream().filter(Checkpoint::landing).findFirst()
                .orElse(path.isEmpty() ? null : path.get(path.size() - 1));

        ObjectNode targetState = Json.obj();
        targetState.put("source_version", currentVersion);
        targetState.put("source_java", currentJava);
        targetState.put("landing_version", landing.version());
        targetState.put("landing_line", landing.line());
        targetState.put("landing_spring_framework_line", landing.springFrameworkLine());
        targetState.put("landing_spring_cloud_train", landing.springCloudTrain());
        targetState.put("landing_java",
                landingEdge == null ? currentJava : String.valueOf(landingEdge.edgeJava()));
        targetState.put("landing_java_version", landingEdge == null ? null : landingEdge.javaVersion());
        targetState.put("landing_java_vendor", landingEdge == null ? null : landingEdge.javaVendor());
        targetState.put("landing_java_home", landingEdge == null ? null : landingEdge.javaHome());
        targetState.put("landing_java_selection_reason",
                landingEdge == null ? "no landing edge was produced" : landingEdge.javaSelectionReason());
        targetState.set("landing_java_supporting_evidence", Json.toTree(
                landingEdge == null ? List.<String>of() : landingEdge.javaSupportingEvidence()));
        targetState.put("landing_java_resolved", landingEdge != null && landingEdge.javaResolved());
        targetState.put("landing_java_is_lts", landingEdge != null
                && com.bootshift.adapters.build.JavaTargetSelector.isLts(landingEdge.edgeJava()));
        targetState.put("java_target_preference", context.policy().javaTargetPreference());
        targetState.set("installed_jdk_majors", Json.toTree(availableJdks));
        targetState.put("selection_mode", selectionMode);
        targetState.put("support_horizon_months", landing.supportHorizonMonths());
        targetState.put("lifecycle_evidence_quality", landing.lifecycleEvidenceQuality());
        targetState.set("cautions", Json.toTree(landing.cautions()));
        targetState.put("frozen", true);
        targetState.put("freeze_note", "Changing a frozen target requires a new run or an explicit "
                + "controlled replan.");
        ObjectNode targetArtifact = StageSupport.compose(envelope, targetState);
        StageSupport.validate(context, writer, "target/target-state.schema.json",
                "target-state.json", targetArtifact);
        writer.write("target-state.json", targetArtifact);

        ObjectNode migrationPath = Json.obj();
        migrationPath.put("edge_count", path.size());
        migrationPath.put("mandatory_checkpoints",
                path.stream().filter(Checkpoint::mandatory).count());
        migrationPath.put("major_boundaries_crossed",
                path.stream().filter(c -> EdgeClass.MAJOR_BOUNDARY.name().equals(c.edgeClass())).count());
        migrationPath.put("majors_between_source_and_landing",
                Math.max(0, majorOf(landing.version()) - majorOf(currentVersion)));
        migrationPath.put("boundary_rule", "One mandatory MAJOR_BOUNDARY edge exists for every major "
                + "version crossed. A path that crosses two majors with one edge is rejected here, "
                + "not discovered later when the compile fails.");
        migrationPath.set("edge_classes", Json.toTree(
                java.util.Arrays.stream(EdgeClass.values()).map(Enum::name).toList()));
        migrationPath.set("edges", Json.toTree(path));
        writer.write("migration-path.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), migrationPath));

        ObjectNode report = renderReport(currentVersion, currentJava, candidates, landing,
                selectionMode, path);
        writer.write("target-resolution-report.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), report));

        // Self-check. The path builder is the only thing standing between a 2.x source and a 4.x
        // landing target crossing two majors in one hop, so its output is asserted rather than
        // trusted. A shortfall here is a harness defect and must stop the run.
        int majorsToCross = Math.max(0, majorOf(landing.version()) - majorOf(currentVersion));
        long boundaryEdges = path.stream()
                .filter(c -> EdgeClass.MAJOR_BOUNDARY.name().equals(c.edgeClass())).count();
        if (boundaryEdges < majorsToCross) {
            StageSupport.publish(context, writer);
            throw HarnessException.stageFailure(
                    "Migration path is missing a mandatory major boundary: crossing from "
                            + currentVersion + " to " + landing.version() + " spans " + majorsToCross
                            + " major version(s) but the path contains only " + boundaryEdges
                            + " MAJOR_BOUNDARY edge(s). Refusing to freeze a path that skips a "
                            + "boundary.", null);
        }
        if (path.stream().noneMatch(Checkpoint::javaResolved)) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-TARGET-JDK", "TOOLCHAIN",
                    "No installed JDK satisfies any edge on the planned path",
                    "Every compile, test and runtime observation on this path would run on an "
                            + "unselected toolchain, so none of them would be evidence"));
        }
        if (landingEdge != null && !landingEdge.javaResolved()) {
            envelope.gap(new Envelope.Gap("GAP-TARGET-JDK", "TOOLCHAIN",
                    "The landing edge has no installed JDK: " + landingEdge.javaSelectionReason(),
                    "The landing target cannot be built on this machine as configured"));
        }
        if (landingEdge != null && !com.bootshift.adapters.build.JavaTargetSelector
                .isLts(landingEdge.edgeJava()) && !context.policy().allowNonLtsJavaLanding()) {
            StageSupport.publish(context, writer);
            throw HarnessException.block("The only admissible Java target for the landing edge is "
                    + landingEdge.edgeJava() + ", which is not a long-term-support release, and "
                    + "policy allow_non_lts_java_landing is false. "
                    + landingEdge.javaSelectionReason());
        }

        StageSupport.toEvidence(context, "target-state", targetArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Target artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.TARGET_RESOLVED, "landing " + landing.version());
        context.stateMachine().transition(RunState.TARGET_FROZEN, "target frozen");
        context.runStateStore().updateState(context.run().runId(), RunState.TARGET_FROZEN,
                "target frozen at " + landing.version());
        context.runStateStore().putAttribute(context.run().runId(), "target_version", landing.version());

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        candidates.stream().filter(c -> !c.viable())
                .forEach(c -> messages.add("eliminated " + c.line() + ": "
                        + String.join("; ", c.eliminations())));
        landing.cautions().forEach(caution ->
                messages.add("CAUTION on the selected target: " + caution
                        + " (raised as an approval gate rather than a silent elimination)"));

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Landing target Spring Boot " + landing.version() + " (Java "
                        + (landingEdge == null ? currentJava : String.valueOf(landingEdge.edgeJava()))
                        + ", Spring Cloud "
                        + landing.springCloudTrain() + "), " + path.size() + " migration edge(s), "
                        + landing.supportHorizonMonths() + " month support horizon",
                messages, artifacts, hash);
    }

    /**
     * Explains a no-viable-target outcome in terms an operator can act on.
     *
     * <p>The common real-world shape is a squeeze: the newest lines have no ecosystem support yet and
     * the lines that do have it have just gone end of life. Saying that plainly, and naming the
     * closest candidate plus the exact policy flag that would admit it, is more useful than a list of
     * rejections.
     */
    private String buildNoViableTargetExplanation(StageContext context, List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("No Spring Boot line satisfies the active policy as a landing target.\n");

        Candidate closest = candidates.stream()
                .filter(c -> c.artifactAvailable() && c.stableGa())
                .filter(c -> c.eliminations().stream()
                        .noneMatch(e -> e.contains("no GA release train")))
                .max(java.util.Comparator.comparingInt(c -> numericLine(c.line())))
                .orElse(null);

        for (Candidate candidate : candidates) {
            sb.append("  ").append(candidate.line()).append(" -> ")
                    .append(String.join("; ", candidate.eliminations())).append('\n');
        }
        if (closest != null) {
            sb.append("\nClosest supportable candidate: ").append(closest.version())
                    .append(" with Spring Cloud ").append(closest.springCloudTrain())
                    .append(".\n");
            sb.append("It was rejected because: ")
                    .append(String.join("; ", closest.eliminations())).append(".\n");
            sb.append("If that is an accepted business risk, re-run with a policy that sets ")
                    .append("allow_eol_landing_target=true, which records the exception explicitly ")
                    .append("rather than hiding it.\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ evaluation

    private Candidate evaluate(StageContext context, JsonNode release, String currentVersion,
                               String currentJava, List<Integer> availableJdks,
                               Map<String, Boolean> artifactAvailable, int unknownInternal,
                               String internalAction, boolean usesSpringCloud) {
        String line = release.path("line").asText();
        String version = release.path("latest_patch").asText(null);
        boolean stableGa = release.path("stable_ga").asBoolean(false);
        boolean eol = release.path("eol").asBoolean(false);
        boolean horizonKnown = !release.path("support_horizon_months").isNull()
                && release.path("support_horizon_months").isNumber();
        long horizon = horizonKnown ? release.path("support_horizon_months").asLong() : 0;
        String quality = release.path("lifecycle_evidence_quality").asText("UNKNOWN");
        boolean lifecycleVerified = "VERIFIED".equals(quality);
        boolean available = artifactAvailable.getOrDefault(line, false);
        List<Integer> javaMajors = new ArrayList<>();
        release.path("supported_java_majors").forEach(n -> javaMajors.add(n.asInt()));
        String cloudTrain = release.path("spring_cloud_train").asText(null);

        List<String> eliminations = new ArrayList<>();
        List<String> cautions = new ArrayList<>();

        // 1. only stable GA candidates may land
        if (!stableGa && !context.policy().allowMilestoneTargets()) {
            eliminations.add("not a stable GA release");
        }
        // 2. never move backwards
        if (version == null || compare(version, currentVersion) <= 0) {
            eliminations.add("not newer than the current state " + currentVersion);
        }
        // 3. artifacts must actually exist
        if (!available) {
            eliminations.add("required artifacts are not available in the configured repositories");
        }
        // 4. the Spring Cloud train must exist when the application uses Spring Cloud. A missing
        // train is a hard constraint for an application that depends on it: the required artifacts
        // simply do not exist for that Boot line yet (R25 step 4).
        if (cloudTrain == null || cloudTrain.isBlank()) {
            if (usesSpringCloud) {
                eliminations.add("the application uses Spring Cloud but no GA release train targets "
                        + "this Boot line, so the required artifacts do not exist");
            } else {
                cautions.add("no published Spring Cloud release train targets this Boot line");
            }
        }
        // 5. Java and toolchain constraints, judged against the JDKs actually installed
        int sourceJava = parseJava(currentJava);
        if (!javaMajors.isEmpty()) {
            boolean buildable = availableJdks.stream()
                    .anyMatch(jdk -> javaMajors.contains(jdk) && jdk >= sourceJava);
            if (!buildable) {
                if (availableJdks.stream().anyMatch(javaMajors::contains)) {
                    eliminations.add("no installed JDK satisfies both the target range " + javaMajors
                            + " and the project level " + currentJava);
                } else {
                    eliminations.add("no installed JDK is in the supported range " + javaMajors
                            + " (installed: " + availableJdks + ")");
                }
            }
        }
        // 6. lifecycle constraints, weighted by how well the lifecycle evidence is established
        if (lifecycleVerified) {
            if (eol && !context.policy().allowEolLandingTarget()) {
                eliminations.add("open-source support has ended");
            } else if (horizonKnown && horizon < context.policy().minimumSupportHorizonMonths()) {
                eliminations.add("support horizon of " + horizon
                        + " month(s) is below the policy minimum "
                        + context.policy().minimumSupportHorizonMonths());
            }
        } else {
            if (eol) {
                cautions.add("lifecycle evidence is " + quality + " and suggests support has ended; "
                        + "this cannot eliminate the target on its own and requires approval");
            } else if (horizonKnown && horizon < context.policy().minimumSupportHorizonMonths()) {
                cautions.add("lifecycle evidence is " + quality + " and suggests only " + horizon
                        + " month(s) of support remain");
            } else if (!horizonKnown) {
                cautions.add("no lifecycle evidence exists for this line; its support window is UNKNOWN");
            }
        }
        // 7. internal components with unknown compatibility (R28)
        if (unknownInternal > 0) {
            if ("BLOCK".equals(internalAction)) {
                eliminations.add(unknownInternal + " internal component(s) have UNKNOWN compatibility "
                        + "and policy action is BLOCK");
            } else {
                cautions.add(unknownInternal + " internal component(s) have UNKNOWN compatibility");
            }
        }

        // Ranking: supportability first, then compatibility quality. Version number is a tiebreak,
        // never the primary criterion (R25).
        double score = (horizonKnown ? horizon : -12) * 10.0
                + (stableGa ? 50 : 0)
                + (available ? 25 : 0)
                + (lifecycleVerified ? 30 : 0)
                + (cloudTrain != null ? 20 : 0)
                + availableJdks.stream().filter(javaMajors::contains).count() * 2
                - cautions.size() * 5
                + numericLine(line) * 0.1;

        return new Candidate(line, version, stableGa, eol, horizon, available,
                release.path("spring_framework_line").asText(null), cloudTrain, javaMajors,
                quality, eliminations, cautions, score);
    }

    /**
     * One migration edge on the path from source to landing.
     *
     * <p>Every edge records why it exists. "Because the planner emitted it" is not a reason a
     * reviewer can check, so each edge carries the lifecycle facts that made it necessary and the
     * toolchain decision taken for it.
     */
    public record Checkpoint(String edgeId, String fromVersion, String toVersion, String edgeClass,
                             boolean landing, boolean mandatory, String rationale,
                             List<String> requiredPreparations, String existsBecause,
                             List<String> supportingEvidence, String springCloudTrain,
                             int edgeJava, String javaVersion, String javaVendor, String javaHome,
                             String javaSelectionReason, List<String> javaSupportingEvidence,
                             boolean javaResolved, boolean transitOnly) {
    }

    /**
     * Edge classes, in the order they can legally appear.
     *
     * <p>MAJOR_BOUNDARY is separated from MINOR because it is the only class that may never be
     * collapsed: it carries the namespace relocation, the security configuration rewrite and the
     * language-level baseline. LANDING is separated from MINOR because a version can be a legal
     * transit checkpoint while being an illegal place to stop.
     */
    public enum EdgeClass {
        PREPARATORY, PATCH, MINOR, MAJOR_BOUNDARY, LANDING
    }

    /**
     * Builds the checkpoint sequence.
     *
     * <p>The major boundary is never collapsed: 2.x to 3.x is modelled as its own edge because it
     * carries the Jakarta namespace change, the Spring Security 6 rewrite and the Java 17 baseline.
     * Test-infrastructure work is modelled as a PREPARATORY edge that runs first (spec section 22).
     */
    /**
     * Builds the checkpoint sequence from source to landing.
     *
     * <p>The rule that matters: EVERY major boundary between the source and the landing target is its
     * own mandatory edge, and the last supported line of a major is reached before the next major is
     * entered. The previous implementation jumped straight to the lowest line of the landing major,
     * so a 2.7 to 4.x migration crossed the Boot 3 boundary and the Boot 4 boundary in one hop and
     * produced no Boot 3 checkpoint at all - which is exactly the transition where the Jakarta
     * relocation and the Spring Security rewrite live.
     */
    private List<Checkpoint> buildPath(StageContext context, String currentVersion, String currentJava,
                                       Candidate landing, List<Candidate> candidates,
                                       List<ToolchainProbe.Jdk> installedJdks) {
        List<Checkpoint> path = new ArrayList<>();
        int index = 0;
        String from = currentVersion;
        String currentLine = lineOf(currentVersion);
        int sourceJava = parseJava(currentJava);

        // 1. Preparatory: test infrastructure moves before any framework change, so pass/fail/skip
        //    semantics are proven to survive on their own.
        path.add(checkpoint(context, "EDGE-" + (++index) + "-PREP-TEST", from, from,
                EdgeClass.PREPARATORY, false, true,
                "Migrate test infrastructure before framework changes so pass/fail/skip semantics "
                        + "are proven to survive independently.",
                List.of("JUnit 4 to Jupiter where present", "assert pass/fail/skip parity"),
                "A framework change and a test-framework change applied together are "
                        + "indistinguishable when a test starts failing.",
                List.of("01-inventory/inventory-signals.json#JUNIT4"),
                candidateFor(currentLine, candidates), sourceJava, installedJdks));

        // 2. Latest patch of the current line: cheap, and removes known defects before the boundary.
        Candidate sameLine = candidateFor(currentLine, candidates);
        if (sameLine != null && sameLine.version() != null && compare(sameLine.version(), from) > 0) {
            path.add(checkpoint(context, "EDGE-" + (++index) + "-PATCH", from, sameLine.version(),
                    EdgeClass.PATCH, false, false,
                    "Move to the latest patch of the current line before the boundary.",
                    List.of(),
                    "Patch releases on the current line carry defect fixes only, so applying them "
                            + "first keeps the boundary edge free of unrelated failures.",
                    List.of("05-compatibility/lifecycle-registry.json#" + sameLine.line()),
                    sameLine, sourceJava, installedJdks));
            from = sameLine.version();
        }

        // 3. Cross each major boundary in turn. Never more than one major per edge.
        int landingMajor = majorOf(landing.version());
        int landingLineOrder = numericLine(landing.line());
        for (int major = majorOf(from) + 1; major <= landingMajor; major++) {
            Candidate entry = lowestLineOfMajor(major, candidates);
            if (entry == null || entry.version() == null) {
                // No published line for this major: the path cannot be built without inventing one.
                break;
            }
            final int boundaryMajor = major;
            boolean isLandingEdge = entry.line().equals(landing.line());
            path.add(checkpoint(context, "EDGE-" + (++index) + "-MAJOR-" + major, from,
                    entry.version(), EdgeClass.MAJOR_BOUNDARY, isLandingEdge, true,
                    majorBoundaryRationale(boundaryMajor),
                    majorBoundaryPreparations(boundaryMajor, entry),
                    "Spring Boot " + boundaryMajor + " is a major boundary between " + from + " and "
                            + landing.version() + ". A mandatory checkpoint exists for every major "
                            + "crossed; none may be skipped or collapsed silently (R26).",
                    List.of("05-compatibility/lifecycle-registry.json#" + entry.line(),
                            "07-documentation/document-registry.json#boot-" + boundaryMajor
                                    + "-migration-guide"),
                    entry, sourceJava, installedJdks));
            from = entry.version();

            // Walk the minor lines of this major. When another major still has to be crossed, walk
            // all the way to the highest line of this major first: that is the supported stepping
            // stone, and entering the next major from an early minor is not a supported path.
            int stopOrder = major == landingMajor
                    ? landingLineOrder : highestLineOrderOfMajor(major, candidates);
            String cursorLine = entry.line();
            while (numericLine(cursorLine) < stopOrder) {
                String stopLine = orderToLine(stopOrder, candidates);
                String nextLine = nextLineAfter(cursorLine, candidates, stopLine);
                if (nextLine == null) {
                    break;
                }
                Candidate next = candidateFor(nextLine, candidates);
                if (next == null || next.version() == null || compare(next.version(), from) <= 0) {
                    break;
                }
                boolean landingHop = major == landingMajor && next.line().equals(landing.line());
                path.add(checkpoint(context,
                        "EDGE-" + (++index) + (landingHop ? "-LANDING" : "-MINOR"),
                        from, next.version(),
                        landingHop ? EdgeClass.LANDING : EdgeClass.MINOR, landingHop, false,
                        landingHop
                                ? "Final hop onto the frozen landing target " + next.version() + "."
                                : "Minor upgrade to " + next.line()
                                        + " carrying deprecations and default changes.",
                        List.of(),
                        landingHop
                                ? "This is the frozen landing target selected by target resolution."
                                : (major == landingMajor
                                        ? "An intermediate minor on the landing major, so "
                                                + "deprecations removed later surface one line at a time."
                                        : "A required stepping stone: the next major may only be "
                                                + "entered from the last supported line of this one."),
                        List.of("05-compatibility/lifecycle-registry.json#" + next.line()),
                        next, sourceJava, installedJdks));
                from = next.version();
                cursorLine = next.line();
            }
        }

        // 4. If nothing above landed, emit the explicit landing hop rather than ending the path short.
        if (path.stream().noneMatch(Checkpoint::landing)) {
            Candidate landingCandidate = candidateFor(landing.line(), candidates);
            path.add(checkpoint(context, "EDGE-" + (++index) + "-LANDING", from, landing.version(),
                    EdgeClass.LANDING, true, false,
                    "Final hop to the frozen landing target.", List.of(),
                    "The preceding checkpoints did not reach the landing line, so an explicit landing "
                            + "edge is emitted rather than silently ending the path early.",
                    List.of("06-target/target-state.json"),
                    landingCandidate == null ? landing : landingCandidate, sourceJava, installedJdks));
        }
        return path;
    }

    /** Builds one checkpoint, including the toolchain decision taken for it. */
    private Checkpoint checkpoint(StageContext context, String edgeId, String fromVersion,
                                  String toVersion, EdgeClass edgeClass, boolean landing,
                                  boolean mandatory, String rationale, List<String> preparations,
                                  String existsBecause, List<String> supportingEvidence,
                                  Candidate targetLine, int sourceJava,
                                  List<ToolchainProbe.Jdk> installedJdks) {
        List<Integer> supported = targetLine == null ? List.of() : targetLine.supportedJavaMajors();
        JavaTargetSelector.Preference preference;
        try {
            preference = JavaTargetSelector.Preference.valueOf(context.policy().javaTargetPreference());
        } catch (IllegalArgumentException e) {
            preference = JavaTargetSelector.Preference.LTS_PREFERRED;
        }
        JavaTargetSelector.Selection selection = new JavaTargetSelector()
                .select(supported, sourceJava, installedJdks, preference);
        return new Checkpoint(edgeId, fromVersion, toVersion, edgeClass.name(), landing, mandatory,
                rationale, preparations, existsBecause, supportingEvidence,
                targetLine == null ? null : targetLine.springCloudTrain(),
                selection.major(), selection.version(), selection.vendor(), selection.home(),
                selection.selectionReason(), selection.supportingEvidence(), selection.resolved(),
                !landing);
    }

    private static String majorBoundaryRationale(int major) {
        if (major == 3) {
            return "Major boundary carrying the Jakarta EE namespace relocation, the Spring Security "
                    + "configuration model change and the Java 17 baseline. This checkpoint is "
                    + "mandatory and may not be collapsed silently (R26).";
        }
        return "Major boundary into Spring Boot " + major + ". A major boundary is always its own "
                + "mandatory checkpoint so its behavioural changes are validated in isolation (R26).";
    }

    private static List<String> majorBoundaryPreparations(int major, Candidate entry) {
        if (major == 3) {
            return List.of("javax to jakarta namespace",
                    "Java " + entry.supportedJavaMajors().stream().min(Integer::compareTo).orElse(17)
                            + " baseline",
                    "Spring Security 6 configuration model",
                    "spring.factories to AutoConfiguration.imports");
        }
        return List.of("review the Spring Boot " + major + " migration guide for removed APIs",
                "confirm the Spring Cloud train published for this line",
                "confirm the language level this line requires");
    }

    private static Candidate candidateFor(String line, List<Candidate> candidates) {
        return candidates.stream().filter(c -> c.line().equals(line)).findFirst().orElse(null);
    }

    private static Candidate lowestLineOfMajor(int major, List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> majorOfLine(c.line()) == major)
                .filter(c -> c.version() != null)
                .min(java.util.Comparator.comparingInt(c -> numericLine(c.line())))
                .orElse(null);
    }

    private static int highestLineOrderOfMajor(int major, List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> majorOfLine(c.line()) == major)
                .filter(c -> c.version() != null)
                .mapToInt(c -> numericLine(c.line()))
                .max()
                .orElse(-1);
    }

    private static String orderToLine(int order, List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> numericLine(c.line()) == order)
                .map(Candidate::line)
                .findFirst()
                .orElse(null);
    }

    static int majorOfLine(String line) {
        return line == null ? -1 : numericLine(line) / 100;
    }

    private ObjectNode renderReport(String currentVersion, String currentJava, List<Candidate> candidates,
                                    Candidate landing, String selectionMode, List<Checkpoint> path) {
        ObjectNode report = Json.obj();
        report.put("source_version", currentVersion);
        report.put("source_java", currentJava);
        report.put("selection_mode", selectionMode);
        report.put("rule", "auto means the highest safe supported stable GA target, not the "
                + "numerically newest (R25)");
        report.put("selected", landing == null ? null : landing.version());
        ArrayNode evaluated = Json.arr();
        candidates.stream()
                .sorted(java.util.Comparator.comparingDouble(Candidate::score).reversed())
                .forEach(c -> {
                    ObjectNode node = Json.obj();
                    node.put("line", c.line());
                    node.put("version", c.version());
                    node.put("stable_ga", c.stableGa());
                    node.put("eol", c.eol());
                    node.put("support_horizon_months", c.supportHorizonMonths());
                    node.put("artifact_available", c.artifactAvailable());
                    node.put("score", Math.round(c.score() * 100.0) / 100.0);
                    node.put("viable", c.viable());
                    node.put("lifecycle_evidence_quality", c.lifecycleEvidenceQuality());
                    node.put("spring_cloud_train", c.springCloudTrain());
                    node.set("eliminations", Json.toTree(c.eliminations()));
                    node.set("cautions", Json.toTree(c.cautions()));
                    node.put("selected", landing != null && landing.line().equals(c.line()));
                    evaluated.add(node);
                });
        report.set("candidates", evaluated);
        report.set("path", Json.toTree(path));
        return report;
    }

    // ------------------------------------------------------------------ version helpers

    private static String nextLineAfter(String currentLine, List<Candidate> candidates, String stopLine) {
        int current = numericLine(currentLine);
        int stop = numericLine(stopLine);
        return candidates.stream()
                .map(Candidate::line)
                .filter(line -> numericLine(line) > current && numericLine(line) <= stop)
                .min(java.util.Comparator.comparingInt(TargetResolverStage::numericLine))
                .orElse(null);
    }

    /** Encodes a "major.minor" line as a sortable integer, e.g. 3.4 becomes 304. */
    static int numericLine(String line) {
        if (line == null) {
            return -1;
        }
        String[] parts = line.split("\\.");
        int major = safeInt(parts[0]);
        int minor = parts.length > 1 ? safeInt(parts[1]) : 0;
        return major * 100 + minor;
    }

    static String lineOf(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    static int majorOf(String version) {
        return safeInt(version.split("\\.")[0]);
    }

    static int parseJava(String value) {
        if (value == null) {
            return 17;
        }
        String cleaned = value.startsWith("1.") ? value.substring(2) : value;
        return safeInt(cleaned.split("\\.")[0]);
    }

    private static int safeInt(String token) {
        try {
            return Integer.parseInt(token.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int compare(String left, String right) {
        return com.bootshift.stages.stage05.CompatibilityStage.compareVersions(left, right);
    }
}
