package com.bootshift.stages.stage05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.compat.MavenCentralVersionSpaceAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.compat.VersionSpacePort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 05 - Compatibility and Lifecycle Registry (spec section 16).
 *
 * <p>Repository-independent Tier-1 version-space knowledge, built <em>before</em> target resolution
 * so the two do not depend on each other circularly.
 *
 * <p>Everything here is evidence-driven rather than hardcoded:
 *
 * <ul>
 *   <li>which release lines exist, and their newest patch, come from artifact repository metadata;</li>
 *   <li>the Spring Boot to Spring Cloud mapping is read out of the published
 *       {@code spring-cloud-starter-parent} POMs, which name their Boot parent directly;</li>
 *   <li>support windows come from a curated table with an expiry date, falling back to a clearly
 *       labelled advisory source;</li>
 *   <li>a dependency is internal only when the public repository says the coordinate does not exist,
 *       which is a test rather than a guess about naming.</li>
 * </ul>
 *
 * <p>Absence of evidence is {@code UNKNOWN}, never "compatible" (R28).
 */
public final class CompatibilityStage implements Stage {

    public static final String OUTPUT_DIR = "05-compatibility";

    private static final Pattern PARENT_BOOT_VERSION = Pattern.compile(
            "<parent>.*?<artifactId>spring-boot-starter-parent</artifactId>.*?"
                    + "<version>([^<]+)</version>", Pattern.DOTALL);

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
        return "Build repository-independent Tier-1 compatibility and lifecycle knowledge";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.BASELINE_SEALED);
    }

    @Override
    public RunState postcondition() {
        return RunState.COMPATIBILITY_REGISTRY_READY;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("02-build/build-model.json", "02-build/dependency-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("compatibility-registry.json", "lifecycle-registry.json",
                "artifact-availability.json", "version-space-evidence.json",
                "internal-components.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");
        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);

        VersionSpacePort versionSpace = new MavenCentralVersionSpaceAdapter(context.http());
        LifecycleSource lifecycleSource = new LifecycleSource(context.http());
        LocalDate today = LocalDate.now();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        // ---- line discovery from artifact metadata (authoritative) -------------------------------
        Optional<VersionSpacePort.ArtifactVersions> published =
                versionSpace.versions("org.springframework.boot", "spring-boot");
        List<String> allVersions = published
                .map(VersionSpacePort.ArtifactVersions::versions).orElse(List.of());
        Map<String, String> latestPatches = new LinkedHashMap<>();
        for (String version : allVersions) {
            if (version.contains("-M") || version.contains("-RC") || version.contains("SNAPSHOT")) {
                continue;
            }
            String line = lineOf(version);
            latestPatches.merge(line, version,
                    (existing, candidate) -> compareVersions(candidate, existing) > 0 ? candidate : existing);
        }
        String currentBoot = buildNode.path("frameworks").path("spring-boot").asText(null);
        String currentJava = buildNode.path("frameworks").path("java").asText("17");
        String currentCloud = buildNode.path("frameworks").path("spring-cloud").asText(null);

        // Only lines at or above the current one are interesting as candidates.
        List<String> candidateLines = latestPatches.keySet().stream()
                .filter(line -> currentBoot == null || numericLine(line) >= numericLine(lineOf(currentBoot)))
                .sorted(java.util.Comparator.comparingInt(CompatibilityStage::numericLine))
                .toList();
        if (candidateLines.isEmpty()) {
            candidateLines = LifecycleSource.curated().stream()
                    .map(LifecycleSource.Line::line).toList();
            LifecycleSource.curated().forEach(line ->
                    latestPatches.putIfAbsent(line.line(), line.latestPatch()));
            envelope.blindSpot(new Envelope.BlindSpot("BS-COMPAT-001", "VERSION_SPACE",
                    "Artifact repository metadata for spring-boot was unreachable",
                    "Candidate lines come from the bundled table and were not confirmed against "
                            + "published artifacts"));
        }

        Map<String, LifecycleSource.Line> lifecycle =
                lifecycleSource.resolve(candidateLines, latestPatches, today);
        boolean stale = LifecycleSource.tableIsStale(today);
        if (stale) {
            envelope.gap(new Envelope.Gap("GAP-COMPAT-003", "LIFECYCLE",
                    "The curated Tier-1 lifecycle table is stale (as of " + LifecycleSource.AS_OF + ")",
                    "Support windows are ADVISORY for this run and cannot eliminate a target on "
                            + "their own"));
        }

        // ---- Spring Cloud train mapping from published artifacts ----------------------------------
        Map<String, String> cloudTrains = resolveCloudTrains(versionSpace, candidateLines);

        // ---- lifecycle registry ------------------------------------------------------------------
        ArrayNode lines = Json.arr();
        for (String line : candidateLines) {
            LifecycleSource.Line facts = lifecycle.get(line);
            ObjectNode node = Json.obj();
            node.put("line", line);
            node.put("latest_patch", facts.latestPatch());
            node.put("general_availability", facts.generalAvailability() == null ? null
                    : facts.generalAvailability().toString());
            node.put("open_source_support_ends", facts.openSourceSupportEnds() == null ? null
                    : facts.openSourceSupportEnds().toString());
            node.put("support_horizon_months", facts.openSourceSupportEnds() == null
                    ? null : facts.supportHorizonMonths(today));
            node.put("eol", facts.openSourceSupportEnds() != null && facts.eol(today));
            node.put("lifecycle_evidence_quality", facts.quality().name());
            node.put("lifecycle_source", facts.source());
            node.put("stable_ga", facts.latestPatch() != null && !facts.latestPatch().contains("-M")
                    && !facts.latestPatch().contains("-RC") && !facts.latestPatch().contains("SNAPSHOT"));
            node.put("spring_cloud_train", cloudTrains.get(line));
            node.put("spring_cloud_train_evidence", trainEvidence.getOrDefault(line,
                    "No Spring Cloud train declares a spring-boot-starter-parent within this Boot "
                            + "major at or below " + line));
            node.set("supported_java_majors", Json.toTree(facts.supportedJavaMajors()));
            lines.add(node);
        }
        ObjectNode lifecycleArtifact = Json.obj();
        lifecycleArtifact.put("component", "spring-boot");
        lifecycleArtifact.put("as_of", LifecycleSource.AS_OF.toString());
        lifecycleArtifact.put("evaluated_on", today.toString());
        lifecycleArtifact.put("curated_table_stale", stale);
        lifecycleArtifact.put("online", versionSpace.online() && published.isPresent());
        lifecycleArtifact.put("published_version_count", allVersions.size());
        lifecycleArtifact.put("rule", "Only VERIFIED lifecycle evidence may eliminate a landing "
                + "target. ADVISORY evidence raises an approval gate instead.");
        lifecycleArtifact.set("releases", lines);
        writer.write("lifecycle-registry.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), lifecycleArtifact));

        // ---- compatibility assertions --------------------------------------------------------------
        ArrayNode assertions = Json.arr();
        for (String line : candidateLines) {
            LifecycleSource.Line facts = lifecycle.get(line);
            if (cloudTrains.containsKey(line)) {
                String evidence = trainEvidence.getOrDefault(line, "");
                assertions.add(assertion("SPRING_BOOT_TO_SPRING_CLOUD", line, cloudTrains.get(line),
                        evidence.contains("matching this line exactly") ? "VERIFIED" : "ADVISORY",
                        evidence));
            } else {
                assertions.add(assertion("SPRING_BOOT_TO_SPRING_CLOUD", line, null, "UNKNOWN",
                        "No published Spring Cloud starter parent targets this Boot line"));
            }
            assertions.add(assertion("SPRING_BOOT_TO_JAVA", line,
                    facts.supportedJavaMajors().toString(),
                    facts.quality() == LifecycleSource.Quality.VERIFIED ? "VERIFIED" : "ADVISORY",
                    facts.source()));
            assertions.add(assertion("SPRING_BOOT_SUPPORT_WINDOW", line,
                    facts.openSourceSupportEnds() == null ? null
                            : facts.openSourceSupportEnds().toString(),
                    facts.quality().name(), facts.source()));
        }
        // Whether the application uses Spring Cloud decides whether a missing release train is a
        // caution or a hard constraint on the target.
        boolean usesSpringCloud = buildModel.dependencies().stream()
                .anyMatch(d -> d.groupId().startsWith("org.springframework.cloud"));

        ObjectNode compatibility = Json.obj();
        compatibility.put("application_uses_spring_cloud", usesSpringCloud);
        compatibility.put("current_spring_boot", currentBoot);
        compatibility.put("current_java", currentJava);
        compatibility.put("current_spring_cloud", currentCloud);
        compatibility.put("running_jdk_major", Runtime.version().feature());
        compatibility.set("available_jdk_majors", Json.toTree(availableJdkMajors()));
        compatibility.put("rule", "Absence of compatibility evidence is UNKNOWN, never compatible");
        compatibility.set("assertions", assertions);
        writer.write("compatibility-registry.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), compatibility));

        // ---- artifact availability -----------------------------------------------------------------
        ArrayNode availability = Json.arr();
        int unavailable = 0;
        for (String line : candidateLines) {
            String candidate = lifecycle.get(line).latestPatch();
            VersionSpacePort.ArtifactExistence existence = candidate == null
                    ? new VersionSpacePort.ArtifactExistence("org.springframework.boot",
                    "spring-boot-starter-parent", null, false, "central", "No published patch known")
                    : versionSpace.exists("org.springframework.boot", "spring-boot-starter-parent",
                    candidate);
            ObjectNode node = Json.obj();
            node.put("line", line);
            node.put("version", candidate);
            node.put("exists", existence.exists());
            node.put("repository", existence.repository());
            node.put("detail", existence.detail());
            availability.add(node);
            if (!existence.exists()) {
                unavailable++;
            }
        }
        ObjectNode availabilityArtifact = Json.obj();
        availabilityArtifact.put("probe", "spring-boot-starter-parent POM existence");
        availabilityArtifact.put("unavailable_count", unavailable);
        availabilityArtifact.set("candidates", availability);
        writer.write("artifact-availability.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), availabilityArtifact));

        // ---- internal component onboarding (R28) -----------------------------------------------------
        ObjectNode internal = onboardInternalComponents(context, buildModel, versionSpace, envelope);
        writer.write("internal-components.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), internal));
        int unknownInternal = internal.path("unknown_count").asInt();

        // ---- version-space evidence -------------------------------------------------------------------
        ObjectNode evidence = Json.obj();
        evidence.put("online", versionSpace.online());
        evidence.put("published_spring_boot_versions", allVersions.size());
        evidence.put("metadata_content_hash",
                published.map(VersionSpacePort.ArtifactVersions::contentHash).orElse(null));
        evidence.put("candidate_lines", candidateLines.size());
        evidence.put("cloud_trains_resolved", cloudTrains.size());
        evidence.put("policy_note", "Community sources are advisory only and never become hard "
                + "compatibility assertions");
        writer.write("version-space-evidence.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), evidence));

        StageSupport.toEvidence(context, "compatibility-registry", compatibility,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.COMPATIBILITY_REGISTRY_READY,
                candidateLines.size() + " candidate line(s)");
        context.runStateStore().updateState(context.run().runId(),
                RunState.COMPATIBILITY_REGISTRY_READY, "compatibility registry ready");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (unknownInternal > 0) {
            messages.add(unknownInternal + " internal component(s) classified UNKNOWN; policy action is "
                    + context.policy().unknownInternalComponentAction());
        }
        if (stale) {
            messages.add("Curated lifecycle table is stale as of " + LifecycleSource.AS_OF
                    + "; support windows are ADVISORY for this run");
        }
        if (!versionSpace.online()) {
            messages.add("Offline mode: compatibility facts were not confirmed against artifact metadata");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Current state Spring Boot " + currentBoot + " / Java " + currentJava
                        + " / Spring Cloud " + currentCloud + "; " + candidateLines.size()
                        + " candidate line(s), " + unavailable + " unavailable, "
                        + unknownInternal + " unknown internal component(s)",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Reads the Boot line each Spring Cloud train targets, out of the train's own starter parent POM.
     *
     * <p>This is artifact-channel evidence: the POM literally declares
     * {@code spring-boot-starter-parent} as its parent, so the mapping is verified rather than
     * remembered.
     */
    private Map<String, String> resolveCloudTrains(VersionSpacePort versionSpace,
                                                   List<String> candidateLines) {
        Map<String, String> byBootLine = new LinkedHashMap<>();
        Optional<VersionSpacePort.ArtifactVersions> trains =
                versionSpace.versions("org.springframework.cloud", "spring-cloud-starter-parent");
        if (trains.isEmpty()) {
            return byBootLine;
        }
        // A single train line spans more than one Boot line over its life: 2021.0.x moved across
        // Boot 2.6 and 2.7 as it was patched. Probing only the newest patch of each train therefore
        // loses the mapping for every earlier Boot line, which matters because transit checkpoints
        // land on exactly those. Probe the newest few patches of each train line instead; responses
        // are content-addressed and cached, so the cost is paid once.
        Map<String, List<String>> patchesPerTrainLine = new LinkedHashMap<>();
        trains.get().versions().stream()
                .filter(v -> !v.contains("-M") && !v.contains("-RC") && !v.contains("SNAPSHOT"))
                .sorted(CompatibilityStage::compareVersions)
                .forEach(version -> patchesPerTrainLine
                        .computeIfAbsent(lineOf(version), k -> new ArrayList<>()).add(version));

        // Probe every stable patch. A train line can move across two Boot lines during its life, and
        // sampling only the newest patches loses whichever Boot line it started on. Responses are
        // content-addressed and cached, so a later run pays nothing.
        List<String> probes = new ArrayList<>();
        patchesPerTrainLine.values().forEach(probes::addAll);
        for (String train : probes) {
            Optional<byte[]> pom = versionSpace.fetchArtifactFile("org.springframework.cloud",
                    "spring-cloud-starter-parent", train, null, "pom");
            if (pom.isEmpty()) {
                continue;
            }
            Matcher matcher = PARENT_BOOT_VERSION.matcher(new String(pom.get(), StandardCharsets.UTF_8));
            if (!matcher.find()) {
                continue;
            }
            trainToBootParent.put(train, lineOf(matcher.group(1)));
        }

        // The parent version a train declares is the Boot version it was built against, which is the
        // *minimum* of its supported range rather than the whole range: Spring Cloud 2021.0.x
        // declares Boot 2.6 and also serves 2.7. Selecting the newest train whose parent sits at or
        // below the target line, and within the same Boot major, reproduces the published pairing
        // without inventing a range the artifacts do not state.
        //
        // The same-major constraint is what keeps this honest at a major boundary: no train declares
        // a Boot 4 parent, so no train is offered for Boot 4, which is the correct answer.
        for (String bootLine : candidateLines) {
            String best = null;
            String bestParent = null;
            for (Map.Entry<String, String> entry : trainToBootParent.entrySet()) {
                String parentLine = entry.getValue();
                if (parentLine == null || majorOfLine(parentLine) != majorOfLine(bootLine)) {
                    continue;
                }
                if (numericLine(parentLine) > numericLine(bootLine)) {
                    continue;
                }
                if (best == null || compareVersions(entry.getKey(), best) > 0) {
                    best = entry.getKey();
                    bestParent = parentLine;
                }
            }
            if (best != null) {
                byBootLine.put(bootLine, best);
                trainEvidence.put(bootLine, bestParent.equals(bootLine)
                        ? "spring-cloud-starter-parent " + best + " declares spring-boot-starter-parent "
                          + bestParent + " as its parent, matching this line exactly"
                        : "spring-cloud-starter-parent " + best + " declares spring-boot-starter-parent "
                          + bestParent + " as its parent; it is the newest train built against a Boot "
                          + "version at or below " + bootLine + " within the same major");
            }
        }
        return byBootLine;
    }

    /** Evidence sentence for each resolved Boot-to-train pairing, keyed by Boot line. */
    private final Map<String, String> trainEvidence = new LinkedHashMap<>();

    /** Raw probe result: Spring Cloud train version to the Boot line its parent declares. */
    private final Map<String, String> trainToBootParent = new LinkedHashMap<>();

    private static int majorOfLine(String line) {
        return numericLine(line) / 100;
    }

    /**
     * Classifies organization-owned dependencies by <em>probing</em> the public repository.
     *
     * <p>A name heuristic would misclassify every public library with an unusual group id. Existence
     * is a test: if the public repository resolves the coordinate, it is a public component; if it
     * does not, it is internal and its compatibility is UNKNOWN until a profile supplies evidence.
     */
    private ObjectNode onboardInternalComponents(StageContext context,
                                                 BuildSystemPort.BuildModel buildModel,
                                                 VersionSpacePort versionSpace, Envelope envelope) {
        Set<String> moduleCoordinates = new LinkedHashSet<>();
        buildModel.modules().forEach(m -> moduleCoordinates.add(m.groupId() + ":" + m.artifactId()));

        Map<String, ObjectNode> components = new LinkedHashMap<>();
        Path profileDir = context.harnessRoot().resolve("policies/default/internal-components");
        int probed = 0;
        int unreachable = 0;

        for (BuildSystemPort.ResolvedDependency dependency : buildModel.dependencies()) {
            String coordinate = dependency.ga();
            if (moduleCoordinates.contains(coordinate) || components.containsKey(coordinate)) {
                continue;
            }
            if (dependency.version() == null || dependency.version().isBlank()) {
                continue;
            }

            Path profile = profileDir.resolve(coordinate.replace(':', '_') + ".json");
            if (Files.isRegularFile(profile)) {
                JsonNode loaded = Json.read(profile);
                ObjectNode node = describe(dependency, "INTERNAL_COMPONENT",
                        loaded.path("compatibility").asText("UNKNOWN"),
                        "InternalComponentProfile at " + profile);
                node.set("profile", loaded);
                components.put(coordinate, node);
                continue;
            }

            probed++;
            VersionSpacePort.ArtifactExistence existence = versionSpace.exists(
                    dependency.groupId(), dependency.artifactId(), dependency.version());
            if (existence.exists()) {
                // Published publicly: a public ecosystem component, not an organization starter.
                continue;
            }
            if (!versionSpace.online()) {
                unreachable++;
                components.put(coordinate, describe(dependency, "UNDETERMINED", "UNKNOWN",
                        "The public repository was unreachable, so the harness cannot tell whether "
                                + "this coordinate is public or internal"));
                continue;
            }
            ObjectNode node = describe(dependency, "INTERNAL_COMPONENT", "UNKNOWN",
                    "The coordinate does not resolve in the configured public repository and no "
                            + "InternalComponentProfile exists, so its supported Boot, Java and "
                            + "framework ranges are UNKNOWN");
            node.put("required_action", context.policy().unknownInternalComponentAction());
            node.put("published_publicly", false);
            components.put(coordinate, node);
        }

        long unknown = components.values().stream()
                .filter(n -> "UNKNOWN".equals(n.path("compatibility").asText()))
                .count();
        if (unknown > 0) {
            envelope.gap(new Envelope.Gap("GAP-COMPAT-002", "INTERNAL_COMPONENT",
                    unknown + " internal or undetermined component(s) have UNKNOWN compatibility",
                    "Target candidates requiring them are constrained by policy action "
                            + context.policy().unknownInternalComponentAction()));
        }

        ObjectNode artifact = Json.obj();
        artifact.put("component_count", components.size());
        artifact.put("unknown_count", unknown);
        artifact.put("coordinates_probed", probed);
        artifact.put("undetermined_due_to_offline", unreachable);
        artifact.put("policy_action", context.policy().unknownInternalComponentAction());
        artifact.put("classification_method", "public artifact repository existence probe, not a "
                + "group-id naming heuristic");
        artifact.put("rule", "An internal component never defaults to compatible (R28)");
        artifact.set("components", Json.toTree(components.values()));
        artifact.put("profile_search_path", profileDir.toString().replace((char) 92, '/'));
        return artifact;
    }

    private static ObjectNode describe(BuildSystemPort.ResolvedDependency dependency,
                                       String classification, String compatibility, String evidence) {
        ObjectNode node = Json.obj();
        node.put("group_id", dependency.groupId());
        node.put("artifact_id", dependency.artifactId());
        node.put("version", dependency.version());
        node.put("module", dependency.module());
        node.put("classification", classification);
        node.put("compatibility", compatibility);
        node.put("evidence", evidence);
        return node;
    }

    private static List<Integer> availableJdkMajors() {
        return new com.bootshift.adapters.build.ToolchainProbe().discover().stream()
                .map(com.bootshift.adapters.build.ToolchainProbe.Jdk::major)
                .distinct()
                .sorted()
                .toList();
    }

    private static ObjectNode assertion(String kind, String subject, String object, String status,
                                        String evidence) {
        ObjectNode node = Json.obj();
        node.put("kind", kind);
        node.put("subject", subject);
        node.put("object", object);
        node.put("status", status);
        node.put("evidence", evidence);
        return node;
    }

    public static String lineOf(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    /** Encodes a "major.minor" line as a sortable integer, e.g. 3.4 becomes 304. */
    public static int numericLine(String line) {
        if (line == null) {
            return -1;
        }
        String[] parts = line.split("\\.");
        int major = safeInt(parts[0]);
        int minor = parts.length > 1 ? safeInt(parts[1]) : 0;
        return major * 100 + minor;
    }

    /** Numeric-aware version comparison so 3.4.10 sorts above 3.4.9. */
    public static int compareVersions(String left, String right) {
        String[] a = left.split("[.\\-]");
        String[] b = right.split("[.\\-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? numeric(a[i]) : -1;
            int y = i < b.length ? numeric(b[i]) : -1;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return left.compareTo(right);
    }

    private static int numeric(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int safeInt(String token) {
        try {
            return Integer.parseInt(token.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String frameworkVersion(JsonNode buildNode, String framework) {
        return buildNode.path("frameworks").path(framework).asText(null);
    }
}
