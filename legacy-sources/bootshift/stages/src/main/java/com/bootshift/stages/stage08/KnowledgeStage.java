package com.bootshift.stages.stage08;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.apidiff.JavapApiDiffAdapter;
import com.bootshift.adapters.compat.MavenCentralVersionSpaceAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.ports.ai.AIProvider;
import com.bootshift.ports.apidiff.ApiDiffPort;
import com.bootshift.ports.compat.VersionSpacePort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 08 - Migration Knowledge Engine (spec section 19).
 *
 * <p>Two mandatory channels. The documentation channel says what the maintainers <em>intended</em>;
 * the artifact channel says what actually changed in the published bytes. A fact is VERIFIED only
 * when the artifact channel confirms it, because only reality can authorize a code change (R10).
 *
 * <p>The artifact channel here computes real BOM diffs, artifact-existence probes and configuration
 * metadata diffs against the published Spring Boot artifacts for the source and target versions.
 * PROPERTY_SILENTLY_IGNORED comes from the configuration-metadata diff and is completed later by the
 * runtime bound-property comparison in Agent 16.
 */
public final class KnowledgeStage implements Stage {

    public static final String OUTPUT_DIR = "08-knowledge";

    private static final Pattern DEPRECATION_PROPERTY = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"([^}]*?)\"deprecation\"\\s*:\\s*\\{([^}]*)}",
            Pattern.DOTALL);
    private static final Pattern REPLACEMENT = Pattern.compile("\"replacement\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEPRECATION_LEVEL = Pattern.compile("\"level\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEPRECATION_REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]{0,300})\"");

    private final AtomicLong sequence = new AtomicLong();

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
        return "Turn official documentation plus artifact reality into verified migration facts";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.DOCUMENTATION_RETRIEVED);
    }

    @Override
    public RunState postcondition() {
        return RunState.KNOWLEDGE_VERIFIED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("06-target/target-state.json", "07-documentation/document-registry.json",
                "02-build/dependency-model.json", "02-build/bom-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("migration-knowledge.json", "artifact-channel.json", "documentation-channel.json",
                "knowledge-summary.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode target = StageSupport.requireUpstream(context, "06-target", "target-state.json",
                "Run: harness resolve-target --target auto");
        JsonNode documents = StageSupport.requireUpstream(context, "07-documentation",
                "document-registry.json", "Run: harness documentation");
        JsonNode dependencies = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");

        String sourceVersion = target.path("source_version").asText();
        String targetVersion = target.path("landing_version").asText();

        // The frozen path. Facts are attributed to the edges they are actually in force on; a fact
        // that spans the whole migration says so rather than being offered to every edge as if it
        // were edge-exact.
        JsonNode migrationPath = StageSupport.optionalUpstream(context, "06-target",
                "migration-path.json");
        List<EdgeSpan> edgeSpans = readEdgeSpans(migrationPath);

        VersionSpacePort versionSpace = new MavenCentralVersionSpaceAdapter(context.http());
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        Map<String, MigrationFact> facts = new LinkedHashMap<>();

        // ---------------------------------------------------------- documentation channel
        ObjectNode documentationChannel = Json.obj();
        ArrayNode extracted = Json.arr();
        int documentsRead = 0;
        for (JsonNode document : documents.path("documents")) {
            String localPath = document.path("local_path").asText(null);
            if (localPath == null || !Files.isRegularFile(Path.of(localPath))) {
                continue;
            }
            documentsRead++;
            String body;
            try {
                body = Files.readString(Path.of(localPath), StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            for (MigrationFact candidate : extractFromDocument(document, body, sourceVersion, targetVersion)) {
                facts.put(candidate.subject() + "|" + candidate.type(), candidate);
                extracted.add(candidate.toNode());
            }
        }
        documentationChannel.put("documents_read", documentsRead);
        documentationChannel.put("candidate_count", extracted.size());
        documentationChannel.put("rule", "Documentation produces CANDIDATE facts only; it never "
                + "authorizes a transformation on its own");
        documentationChannel.set("candidates", extracted);

        // ---------------------------------------------------------- artifact channel
        ObjectNode artifactChannel = Json.obj();
        List<ObjectNode> artifactObservations = new ArrayList<>();

        Optional<VersionSpacePort.BomSnapshot> sourceBom = versionSpace.bom(
                "org.springframework.boot", "spring-boot-dependencies", sourceVersion);
        Optional<VersionSpacePort.BomSnapshot> targetBom = versionSpace.bom(
                "org.springframework.boot", "spring-boot-dependencies", targetVersion);

        // Ordered by closeness to the application, because the bytecode diff has a budget and
        // spending it on the far end of the transitive closure is how the removal that actually
        // blocks a corpus goes unnoticed. Directly declared coordinates first, then the Spring
        // ecosystem, then everything else.
        Set<String> directCoordinates = new LinkedHashSet<>();
        Set<String> springCoordinates = new LinkedHashSet<>();
        Set<String> otherCoordinates = new LinkedHashSet<>();
        for (JsonNode dependency : dependencies.path("dependencies")) {
            String group = dependency.path("groupId").asText("");
            String artifact = dependency.path("artifactId").asText("");
            if (group.isBlank() || artifact.isBlank()) {
                continue;
            }
            String coordinate = group + ":" + artifact;
            if (dependency.path("direct").asBoolean(false)) {
                directCoordinates.add(coordinate);
            } else if (group.startsWith("org.springframework")) {
                springCoordinates.add(coordinate);
            } else {
                otherCoordinates.add(coordinate);
            }
        }
        Set<String> applicationCoordinates = new LinkedHashSet<>();
        applicationCoordinates.addAll(directCoordinates);
        applicationCoordinates.addAll(springCoordinates);
        applicationCoordinates.addAll(otherCoordinates);

        // The Spring Cloud train is versioned independently of Spring Boot and the Boot BOM does not
        // manage a single Spring Cloud artifact. A bytecode diff that reads only the Boot BOM is
        // blind to exactly the half of a Spring migration that breaks first: @EnableEurekaClient
        // lives in spring-cloud-netflix-eureka-client, and its removal produced 33 compile errors on
        // the reference corpus while the knowledge base reported near-total coverage.
        JsonNode compatibility = StageSupport.optionalUpstream(context, "05-compatibility",
                "compatibility-registry.json");
        Optional<VersionSpacePort.BomSnapshot> sourceCloudBom = cloudBom(versionSpace,
                compatibility == null ? null : compatibility.path("current_spring_cloud").asText(null));
        Optional<VersionSpacePort.BomSnapshot> targetCloudBom = cloudBom(versionSpace,
                target.path("landing_spring_cloud_train").asText(null));

        if (sourceBom.isPresent() && targetBom.isPresent()) {
            Map<String, String> before = new LinkedHashMap<>();
            sourceBom.get().entries().forEach(e -> before.put(e.groupId() + ":" + e.artifactId(), e.version()));
            Map<String, String> after = new LinkedHashMap<>();
            targetBom.get().entries().forEach(e -> after.put(e.groupId() + ":" + e.artifactId(), e.version()));

            int managedChanged = 0;
            int removed = 0;
            for (Map.Entry<String, String> entry : before.entrySet()) {
                String coordinate = entry.getKey();
                boolean used = applicationCoordinates.contains(coordinate);
                if (!after.containsKey(coordinate)) {
                    if (used) {
                        removed++;
                        MigrationFact fact = fact(MigrationFact.Type.ARTIFACT_REMOVED, coordinate)
                                .versions(sourceVersion, targetVersion)
                                .component(componentOf(coordinate))
                                .from(entry.getValue())
                                .summary(coordinate + " is no longer managed by the target BOM")
                                .detectionRule("BOM_DIFF_REMOVED")
                                .verifyWithArtifactEvidence("spring-boot-dependencies " + sourceVersion
                                        + " manages " + coordinate + " but " + targetVersion + " does not");
                        merge(facts, fact);
                        artifactObservations.add(fact.toNode());
                    }
                    continue;
                }
                String newVersion = after.get(coordinate);
                if (!entry.getValue().equals(newVersion) && used) {
                    managedChanged++;
                    MigrationFact fact = fact(MigrationFact.Type.MANAGED_VERSION_CHANGED, coordinate)
                            .versions(sourceVersion, targetVersion)
                            .component(componentOf(coordinate))
                            .from(entry.getValue()).to(newVersion)
                            .summary(coordinate + " managed version changes from " + entry.getValue()
                                    + " to " + newVersion)
                            .detectionRule("BOM_DIFF_VERSION")
                            .verifyWithArtifactEvidence("spring-boot-dependencies BOM diff "
                                    + sourceVersion + " -> " + targetVersion);
                    merge(facts, fact);
                    artifactObservations.add(fact.toNode());
                }
            }
            artifactChannel.put("bom_source_entries", before.size());
            artifactChannel.put("bom_target_entries", after.size());
            artifactChannel.put("managed_versions_changed_for_application", managedChanged);
            artifactChannel.put("managed_artifacts_removed_for_application", removed);
            artifactChannel.put("bom_source_hash", sourceBom.get().contentHash());
            artifactChannel.put("bom_target_hash", targetBom.get().contentHash());
        } else {
            envelope.blindSpot(new Envelope.BlindSpot("BS-KNOW-001", "ARTIFACT_CHANNEL",
                    "Source or target BOM could not be retrieved",
                    "Managed version and artifact removal facts could not be computed from artifact "
                            + "reality, so documentation candidates cannot be promoted to VERIFIED"));
        }

        // ---------------------------------------------------------- per-edge BOM attribution
        // The whole-migration BOM diff above says a coordinate changed somewhere between source and
        // landing. Diffing the BOM at each edge boundary says WHERE, which is what makes a fact
        // usable as authorization for one edge rather than for all of them. The BOM fetches are
        // content-addressed and cached, so this costs one request per distinct version.
        Map<String, List<EdgeSpan>> versionChangeEdges = new LinkedHashMap<>();
        ObjectNode edgeAttribution = Json.obj();
        int edgeBomsResolved = 0;
        if (!edgeSpans.isEmpty()) {
            Map<String, Map<String, String>> bomByVersion = new LinkedHashMap<>();
            for (EdgeSpan span : edgeSpans) {
                for (String version : List.of(span.from(), span.to())) {
                    if (bomByVersion.containsKey(version)) {
                        continue;
                    }
                    Optional<VersionSpacePort.BomSnapshot> snapshot = versionSpace.bom(
                            "org.springframework.boot", "spring-boot-dependencies", version);
                    if (snapshot.isEmpty()) {
                        continue;
                    }
                    Map<String, String> managed = new LinkedHashMap<>();
                    snapshot.get().entries()
                            .forEach(e -> managed.put(e.groupId() + ":" + e.artifactId(), e.version()));
                    bomByVersion.put(version, managed);
                }
            }
            edgeBomsResolved = bomByVersion.size();
            for (EdgeSpan span : edgeSpans) {
                Map<String, String> before = bomByVersion.get(span.from());
                Map<String, String> after = bomByVersion.get(span.to());
                if (before == null || after == null) {
                    continue;
                }
                for (Map.Entry<String, String> entry : before.entrySet()) {
                    String coordinate = entry.getKey();
                    if (!applicationCoordinates.contains(coordinate)) {
                        continue;
                    }
                    String now = after.get(coordinate);
                    if (now == null || !now.equals(entry.getValue())) {
                        versionChangeEdges.computeIfAbsent(coordinate, k -> new ArrayList<>()).add(span);
                    }
                }
            }
            ObjectNode perCoordinate = Json.obj();
            versionChangeEdges.forEach((coordinate, spans) -> perCoordinate.set(coordinate,
                    Json.toTree(spans.stream().map(EdgeSpan::edgeId).toList())));
            edgeAttribution.put("edge_boms_resolved", edgeBomsResolved);
            edgeAttribution.put("coordinates_attributed", versionChangeEdges.size());
            edgeAttribution.set("version_change_edges", perCoordinate);
            edgeAttribution.put("rule", "A fact about an artifact can only be in force on an edge "
                    + "where that artifact's managed version actually moved.");
        } else {
            edgeAttribution.put("edge_boms_resolved", 0);
            edgeAttribution.put("reason", "No frozen migration path was available, so facts keep "
                    + "whole-migration validity and are marked SPAN_ONLY");
        }
        artifactChannel.set("edge_attribution", edgeAttribution);

        // Artifact-existence probes for every coordinate the application declares explicitly.
        int missingAtTarget = 0;
        for (JsonNode dependency : dependencies.path("dependencies")) {
            if (!dependency.path("direct").asBoolean(false)) {
                continue;
            }
            String group = dependency.path("groupId").asText();
            String artifact = dependency.path("artifactId").asText();
            if (!group.startsWith("org.springframework")) {
                continue;
            }
            String candidateVersion = targetBom
                    .flatMap(bom -> bom.entries().stream()
                            .filter(e -> e.groupId().equals(group) && e.artifactId().equals(artifact))
                            .map(VersionSpacePort.BomEntry::version).findFirst())
                    .orElse(null);
            if (candidateVersion == null) {
                continue;
            }
            VersionSpacePort.ArtifactExistence existence =
                    versionSpace.exists(group, artifact, candidateVersion);
            if (!existence.exists()) {
                missingAtTarget++;
                MigrationFact fact = fact(MigrationFact.Type.ARTIFACT_REMOVED, group + ":" + artifact)
                        .versions(sourceVersion, targetVersion)
                        .component(componentOf(group, artifact))
                        .summary(group + ":" + artifact + " does not resolve at the target version")
                        .detectionRule("ARTIFACT_EXISTENCE_PROBE")
                        .verifyWithArtifactEvidence(existence.detail());
                merge(facts, fact);
                artifactObservations.add(fact.toNode());
            }
        }
        artifactChannel.put("artifacts_missing_at_target", missingAtTarget);

        // Published-bytecode diff. BOM entries, existence probes and configuration metadata all
        // describe the packaging around a dependency; only javap over the two jars says what types
        // the application can still reference. Skipping it is how an edge reaches the compiler with
        // a knowledge base that reported near-total coverage: nothing had looked inside the jars.
        ApiDiffPort apiDiff = new JavapApiDiffAdapter();
        ObjectNode apiDiffSummary = Json.obj();
        apiDiffSummary.put("tool", apiDiff.name());
        apiDiffSummary.put("available", apiDiff.available());
        int artifactsDiffed = 0;
        int typesRemoved = 0;
        List<String> notDiffed = new ArrayList<>();
        List<VersionSpacePort.BomSnapshot> sourceBoms = presentOf(sourceBom, sourceCloudBom);
        List<VersionSpacePort.BomSnapshot> targetBoms = presentOf(targetBom, targetCloudBom);
        if (apiDiff.available() && !sourceBoms.isEmpty() && !targetBoms.isEmpty()) {
            for (String coordinate : applicationCoordinates) {
                String[] parts = coordinate.split(":");
                if (parts.length != 2) {
                    continue;
                }
                String oldVersion = bomVersion(sourceBoms, parts[0], parts[1]);
                String newVersion = bomVersion(targetBoms, parts[0], parts[1]);
                if (isStarter(parts[1])) {
                    // A starter ships no classes; it exists to pull in a dependency set. Diffing one
                    // is guaranteed to find nothing, and on this corpus starters consumed a third of
                    // the budget while the artifact carrying the blocking removal never got a slot.
                    notDiffed.add(coordinate + " (starter artifact: ships no classes to diff)");
                    continue;
                }
                if (oldVersion == null || newVersion == null || oldVersion.equals(newVersion)) {
                    if (oldVersion == null || newVersion == null) {
                        notDiffed.add(coordinate + " (not managed by both the source and target BOMs)");
                    }
                    continue;
                }
                if (artifactsDiffed >= MAX_ARTIFACTS_DIFFED) {
                    notDiffed.add(coordinate + " (per-run bytecode diff budget of "
                            + MAX_ARTIFACTS_DIFFED + " artifacts reached)");
                    continue;
                }
                Optional<Path> oldJar = jar(context, versionSpace, parts[0], parts[1], oldVersion);
                Optional<Path> newJar = jar(context, versionSpace, parts[0], parts[1], newVersion);
                if (oldJar.isEmpty() || newJar.isEmpty()) {
                    notDiffed.add(coordinate + " (jar could not be retrieved)");
                    continue;
                }
                ApiDiffPort.DiffResult diff = apiDiff.compare(oldJar.get(), newJar.get(),
                        parts[0], parts[1], oldVersion, newVersion);
                artifactsDiffed++;
                if (!diff.complete()) {
                    notDiffed.add(coordinate + " (incomplete: " + diff.incompleteReason() + ")");
                }
                for (ApiDiffPort.ApiChange change : diff.changes()) {
                    if (change.kind() != ApiDiffPort.ChangeKind.TYPE_REMOVED) {
                        continue;
                    }
                    typesRemoved++;
                    MigrationFact removed = fact(MigrationFact.Type.API_REMOVED, change.type())
                            .versions(sourceVersion, targetVersion)
                            .component(componentOf(parts[0], parts[1]))
                            .summary(change.type() + " is present in " + coordinate + " "
                                    + oldVersion + " and absent in " + newVersion)
                            .detectionRule("PUBLISHED_BYTECODE_DIFF")
                            .verifyWithArtifactEvidence(diff.toolName() + " over "
                                    + coordinate + ":" + oldVersion + " and :" + newVersion);
                    narrowToVersionChangeEdges(removed, versionChangeEdges.get(coordinate),
                            sourceVersion, targetVersion);
                    merge(facts, removed);
                    artifactObservations.add(removed.toNode());
                }
            }
        } else if (!apiDiff.available()) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-KNOW-002", "ARTIFACT_CHANNEL",
                    "javap is not available, so no published-bytecode diff was performed",
                    "Removed and relocated types cannot be detected from artifact reality; only "
                            + "documentation candidates are available for those subjects"));
        }
        apiDiffSummary.put("artifacts_diffed", artifactsDiffed);
        apiDiffSummary.put("artifact_budget", MAX_ARTIFACTS_DIFFED);
        apiDiffSummary.put("diff_order", "directly declared coordinates first, then the Spring "
                + "ecosystem, then the rest of the transitive closure");
        apiDiffSummary.put("boms_consulted", sourceBoms.size() + " source, "
                + targetBoms.size() + " target (spring-boot-dependencies and, when a train is resolved, spring-cloud-dependencies)");
        apiDiffSummary.put("types_removed_at_target", typesRemoved);
        apiDiffSummary.set("not_diffed", Json.toTree(notDiffed));
        artifactChannel.set("api_diff", apiDiffSummary);
        if (!notDiffed.isEmpty()) {
            envelope.gap(new Envelope.Gap("GAP-KNOW-002", "API_DIFF_COVERAGE",
                    notDiffed.size() + " referenced coordinate(s) could not be diffed at the "
                            + "bytecode level",
                    "Removed types in those artifacts are invisible to the artifact channel; a "
                            + "compile failure on such a type will surface as residual rather than "
                            + "as a planned change"));
        }

        // Configuration metadata diff: the source of generated property migration rules.
        ConfigurationMetadataDiff metadataDiff = diffConfigurationMetadata(
                versionSpace, sourceVersion, targetVersion);
        for (PropertyChange change : metadataDiff.changes()) {
            MigrationFact.Type type = change.replacement() == null || change.replacement().isBlank()
                    ? MigrationFact.Type.PROPERTY_REMOVED : MigrationFact.Type.PROPERTY_RENAMED;
            MigrationFact fact = fact(type, change.property())
                    .versions(sourceVersion, targetVersion)
                    .component(componentOfArtifact(change.artifact()))
                    .validity(sourceVersion, targetVersion,
                            MigrationFact.ValidityPrecision.SPAN_ONLY)
                    .from(change.property()).to(change.replacement())
                    .summary(change.reason() == null
                            ? change.property() + " is deprecated at the target version"
                            : change.reason())
                    .detectionRule("CONFIGURATION_METADATA_DEPRECATION")
                    .verifyWithArtifactEvidence("spring-configuration-metadata.json for "
                            + change.artifact() + " " + targetVersion + " declares deprecation level "
                            + change.level());
            merge(facts, fact);
            artifactObservations.add(fact.toNode());
        }
        // Generate deterministic property migration rules from the official deprecation metadata.
        // Hand-writing hundreds of these is how they end up wrong; generating them keeps the rules
        // and the evidence that produced them in lockstep (spec section 23).
        int generatedRules = writeGeneratedPropertyRules(context, metadataDiff, sourceVersion,
                targetVersion);
        artifactChannel.put("generated_property_rules", generatedRules);
        artifactChannel.put("generated_property_rules_path",
                generatedRuleFile(context).toString().replace((char) 92, '/'));
        artifactChannel.put("configuration_metadata_artifacts_read", metadataDiff.artifactsRead());
        artifactChannel.put("deprecated_properties_at_target", metadataDiff.changes().size());
        artifactChannel.put("silently_ignored_note",
                "PROPERTY_SILENTLY_IGNORED is completed by the runtime bound-property comparison in "
                        + "Agent 16; metadata alone shows deprecation, not binding behaviour");
        artifactChannel.set("observations", Json.toTree(artifactObservations));

        // ---------------------------------------------------------- structural facts from the edge
        addStructuralEdgeFacts(facts, sourceVersion, targetVersion, applicationCoordinates);
        scopeStructuralFactsToBoundary(facts, edgeSpans, sourceVersion, targetVersion);

        // ---------------------------------------------------------- optional AI assistance
        ObjectNode aiSection = Json.obj();
        aiSection.put("enabled", context.ai().enabled());
        if (context.ai().enabled()) {
            aiSection.put("role", "candidate extraction and clustering only; every proposal remains a "
                    + "hypothesis until deterministic verification succeeds (R11)");
            Optional<AIProvider.Proposal> proposal = context.ai().propose(
                    AIProvider.Task.CANDIDATE_KNOWLEDGE_EXTRACTION,
                    "List migration-relevant breaking changes between Spring Boot " + sourceVersion
                            + " and " + targetVersion + ". Respond with one change per line.",
                    Map.of("source", sourceVersion, "target", targetVersion), List.of());
            proposal.ifPresent(p -> {
                aiSection.put("proposal_id", p.proposalId());
                aiSection.put("prompt_hash", p.promptHash());
                aiSection.put("response_hash", p.responseHash());
                aiSection.set("model", Json.toTree(p.model()));
                aiSection.put("accepted_facts", 0);
                aiSection.put("note", "AI output was recorded as evidence but produced no VERIFIED fact "
                        + "because no artifact-channel observation corroborated it");
            });
        } else {
            aiSection.put("role", "disabled; the knowledge engine is fully deterministic in this run");
        }

        // ---------------------------------------------------------- publish
        List<MigrationFact> allFacts = new ArrayList<>(facts.values());
        long verified = allFacts.stream().filter(f -> f.status() == MigrationFact.Status.VERIFIED).count();
        long candidates = allFacts.stream().filter(f -> f.status() == MigrationFact.Status.CANDIDATE).count();
        long conflicting = allFacts.stream()
                .filter(f -> f.status() == MigrationFact.Status.CONFLICTING).count();

        envelope.stat("facts_total", allFacts.size())
                .stat("facts_verified", verified)
                .stat("facts_candidate", candidates)
                .stat("facts_conflicting", conflicting);

        ObjectNode knowledge = Json.obj();
        knowledge.put("source_version", sourceVersion);
        knowledge.put("target_version", targetVersion);
        knowledge.put("fact_count", allFacts.size());
        knowledge.put("verified_count", verified);
        knowledge.put("rule", "Only VERIFIED facts can authorize automatic transformation");
        ArrayNode factArray = Json.arr();
        allFacts.forEach(f -> factArray.add(f.toNode()));
        knowledge.set("facts", factArray);
        knowledge.set("ai", aiSection);
        ObjectNode knowledgeArtifact = StageSupport.compose(envelope, knowledge);
        StageSupport.validate(context, writer, "migration-knowledge/migration-knowledge.schema.json",
                "migration-knowledge.json", knowledgeArtifact);
        writer.write("migration-knowledge.json", knowledgeArtifact);

        writer.write("artifact-channel.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), artifactChannel));
        writer.write("documentation-channel.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), documentationChannel));

        ObjectNode summary = Json.obj();
        Map<String, Long> byType = new java.util.TreeMap<>();
        allFacts.forEach(f -> byType.merge(f.type().name(), 1L, Long::sum));
        summary.set("by_type", Json.toTree(byType));
        Map<String, Long> byStatus = new java.util.TreeMap<>();
        allFacts.forEach(f -> byStatus.merge(f.status().name(), 1L, Long::sum));
        summary.set("by_status", Json.toTree(byStatus));
        Map<String, Long> byChannel = new java.util.TreeMap<>();
        allFacts.forEach(f -> byChannel.merge(f.channel().name(), 1L, Long::sum));
        summary.set("by_channel", Json.toTree(byChannel));
        writer.write("knowledge-summary.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), summary));

        StageSupport.toEvidence(context, "migration-knowledge", knowledgeArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Knowledge artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.KNOWLEDGE_VERIFIED,
                verified + " verified fact(s)");
        context.runStateStore().updateState(context.run().runId(), RunState.KNOWLEDGE_VERIFIED,
                "knowledge verified");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (candidates > 0) {
            messages.add(candidates + " fact(s) remain CANDIDATE: documentation asserted them but no "
                    + "artifact observation corroborated them, so they cannot authorize a change");
        }
        if (conflicting > 0) {
            messages.add(conflicting + " CONFLICTING fact(s) require human resolution");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                allFacts.size() + " migration fact(s): " + verified + " VERIFIED, " + candidates
                        + " CANDIDATE, " + conflicting + " CONFLICTING for " + sourceVersion
                        + " -> " + targetVersion,
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ documentation channel

    private List<MigrationFact> extractFromDocument(JsonNode document, String body,
                                                    String sourceVersion, String targetVersion) {
        List<MigrationFact> found = new ArrayList<>();
        String documentId = document.path("document_id").asText();
        boolean advisoryOnly = "COMMUNITY_ADVISORY".equals(document.path("trust_level").asText());

        for (DocumentationPatterns.Extraction extraction : DocumentationPatterns.extract(body)) {
            MigrationFact fact = fact(extraction.type(), extraction.subject())
                    .versions(sourceVersion, targetVersion)
                    // The document was retrieved for one specific edge, so the candidate it produces
                    // is in force on that edge only. Carrying the whole migration's span here is what
                    // let a Boot 3.0 migration guide be offered as authorization for the 3.3 edge.
                    .validity(document.path("source_version").asText(sourceVersion),
                            document.path("target_version").asText(targetVersion),
                            MigrationFact.ValidityPrecision.EDGE_EXACT)
                    .component(document.path("component").asText("spring-boot"))
                    .from(extraction.subject())
                    .to(extraction.replacement())
                    .summary(extraction.sentence())
                    .document(documentId)
                    .detectionRule(extraction.rule())
                    .confidence(advisoryOnly ? 0.2 : 0.55)
                    .status(MigrationFact.Status.CANDIDATE);
            found.add(fact);
        }
        return found;
    }

    private static String excerpt(String body, int start) {
        int from = Math.max(0, start - 40);
        int to = Math.min(body.length(), start + 200);
        return body.substring(from, to).replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ artifact channel

    record PropertyChange(String property, String replacement, String level, String reason,
                          String artifact) {
    }

    record ConfigurationMetadataDiff(int artifactsRead, List<PropertyChange> changes) {
    }

    /**
     * Diffs {@code spring-configuration-metadata.json} between the source and target releases.
     *
     * <p>Official deprecation entries carry the replacement key, which is exactly what a generated
     * property migration rule needs. Writing hundreds of such rules by hand is how they end up wrong.
     */
    private ConfigurationMetadataDiff diffConfigurationMetadata(VersionSpacePort versionSpace,
                                                                String sourceVersion,
                                                                String targetVersion) {
        List<PropertyChange> changes = new ArrayList<>();
        int read = 0;
        for (String artifact : List.of("spring-boot", "spring-boot-autoconfigure",
                "spring-boot-actuator-autoconfigure")) {
            Optional<byte[]> targetJar = versionSpace.fetchArtifactFile(
                    "org.springframework.boot", artifact, targetVersion, null, "jar");
            if (targetJar.isEmpty()) {
                continue;
            }
            String metadata = extractMetadata(targetJar.get());
            if (metadata == null) {
                continue;
            }
            read++;
            Matcher matcher = DEPRECATION_PROPERTY.matcher(metadata);
            int guard = 0;
            while (matcher.find() && guard++ < 5000) {
                String property = matcher.group(1);
                String deprecationBlock = matcher.group(3);
                Matcher replacement = REPLACEMENT.matcher(deprecationBlock);
                Matcher level = DEPRECATION_LEVEL.matcher(deprecationBlock);
                Matcher reason = DEPRECATION_REASON.matcher(deprecationBlock);
                changes.add(new PropertyChange(property,
                        replacement.find() ? replacement.group(1) : null,
                        level.find() ? level.group(1) : "warning",
                        reason.find() ? reason.group(1) : null,
                        artifact));
            }
        }
        return new ConfigurationMetadataDiff(read, changes);
    }

    /**
     * Writes the generated property migration rules used by Agent 12.
     *
     * <p>Each rule carries the evidence that produced it, so a reviewer can trace any property
     * rewrite back to the exact deprecation entry in the target artifact's metadata.
     */
    private int writeGeneratedPropertyRules(StageContext context, ConfigurationMetadataDiff diff,
                                            String sourceVersion, String targetVersion) {
        ObjectNode artifact = Json.obj();
        artifact.put("generated_by", "bootshift 08-knowledge");
        artifact.put("source_version", sourceVersion);
        artifact.put("target_version", targetVersion);
        artifact.put("generated_at", java.time.Instant.now().toString());
        artifact.put("provenance", "spring-configuration-metadata.json deprecation entries published "
                + "in the target release artifacts");
        artifact.put("hand_written", false);

        ArrayNode rules = Json.arr();
        for (PropertyChange change : diff.changes()) {
            ObjectNode rule = Json.obj();
            rule.put("from", change.property());
            rule.put("to", change.replacement());
            rule.put("action", change.replacement() == null || change.replacement().isBlank()
                    ? "REMOVE" : "RENAME");
            rule.put("reason", change.reason() == null
                    ? "Deprecated at " + targetVersion + " with deprecation level " + change.level()
                    : change.reason());
            rule.put("deprecation_level", change.level());
            rule.put("evidence_ref", "META-INF/spring-configuration-metadata.json in "
                    + change.artifact() + " " + targetVersion);
            rules.add(rule);
        }
        artifact.put("rule_count", rules.size());
        artifact.set("rules", rules);
        Json.write(generatedRuleFile(context), artifact);
        return rules.size();
    }

    static Path generatedRuleFile(StageContext context) {
        return context.migrationRules().resolve("generated-properties/property-migration-rules.json");
    }

    /** Pulls META-INF/spring-configuration-metadata.json out of a jar held in memory. */
    static String extractMetadata(byte[] jarBytes) {
        try (java.util.zip.ZipInputStream zip =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("META-INF/spring-configuration-metadata.json")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ structural edge facts

    /**
     * Facts that follow structurally from the edge itself rather than from a document or a diff.
     *
     * <p>These are verified by construction: the Jakarta relocation and the Spring Security
     * configuration model change are properties of the Spring Boot 3 boundary, and the harness
     * confirms each one against the application's own resolved dependencies before emitting it.
     */
    private void addStructuralEdgeFacts(Map<String, MigrationFact> facts, String sourceVersion,
                                        String targetVersion, Set<String> applicationCoordinates) {
        int sourceMajor = majorOf(sourceVersion);
        int targetMajor = majorOf(targetVersion);
        if (sourceMajor >= 3 || targetMajor < 3) {
            return;
        }

        merge(facts, fact(MigrationFact.Type.API_RENAMED, "javax.* to jakarta.*")
                .versions(sourceVersion, targetVersion)
                .from("javax").to("jakarta")
                .summary("Spring Boot 3 moves to Jakarta EE 9+, relocating the javax.persistence, "
                        + "javax.servlet, javax.validation and related packages to jakarta.*")
                .detectionRule("MAJOR_BOUNDARY_STRUCTURAL")
                .verifyWithArtifactEvidence("Target BOM manages jakarta.* artifacts in place of the "
                        + "javax.* equivalents"));

        merge(facts, fact(MigrationFact.Type.BASELINE_REQUIREMENT, "java.version")
                .versions(sourceVersion, targetVersion)
                .from("17").to("17")
                .summary("Spring Boot 3 requires Java 17 as a hard baseline")
                .detectionRule("MAJOR_BOUNDARY_STRUCTURAL")
                .verifyWithArtifactEvidence("Target artifacts are compiled for class file version 61"));

        if (applicationCoordinates.stream().anyMatch(c -> c.contains("spring-boot-starter-security")
                || c.contains("spring-security"))) {
            merge(facts, fact(MigrationFact.Type.API_REMOVED,
                    "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter")
                    .versions(sourceVersion, targetVersion)
                    .summary("WebSecurityConfigurerAdapter is removed in Spring Security 6; security "
                            + "configuration moves to SecurityFilterChain beans")
                    .detectionRule("MAJOR_BOUNDARY_STRUCTURAL")
                    .verifyWithArtifactEvidence("Type absent from spring-security-config at the target "
                            + "managed version"));
        }

        if (applicationCoordinates.stream().anyMatch(c -> c.startsWith("org.springframework.cloud:"))) {
            merge(facts, fact(MigrationFact.Type.COMPATIBILITY_REQUIREMENT, "spring-cloud-dependencies")
                    .versions(sourceVersion, targetVersion)
                    .summary("The Spring Cloud release train is version-locked to the Spring Boot line "
                            + "and must move together with it")
                    .detectionRule("COMPATIBILITY_MATRIX")
                    .verifyWithArtifactEvidence("Compatibility registry assertion "
                            + "SPRING_BOOT_TO_SPRING_CLOUD"));
        }

        merge(facts, fact(MigrationFact.Type.BEHAVIOR_CHANGED_NO_API_CHANGE, "spring.factories")
                .versions(sourceVersion, targetVersion)
                .from("META-INF/spring.factories")
                .to("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .summary("Auto-configuration registration moves out of spring.factories; entries left "
                        + "behind are silently ignored rather than failing")
                .detectionRule("MAJOR_BOUNDARY_STRUCTURAL")
                .verifyWithArtifactEvidence("Target spring-boot-autoconfigure ships the "
                        + "AutoConfiguration.imports resource"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * How many jar pairs one run will download and diff.
     *
     * <p>The bound exists so a large corpus cannot turn one stage into a hundred-megabyte download.
     * Coordinates beyond it are recorded in {@code not_diffed} and raise a gap, so the limit shows up
     * as reduced coverage rather than as silently missing facts.
     */
    private static final int MAX_ARTIFACTS_DIFFED = 60;

    /** True for packaging-only aggregator artifacts, which carry no types to compare. */
    public static boolean isStarter(String artifactId) {
        return artifactId.endsWith("-starter") || artifactId.contains("-starter-")
                || artifactId.endsWith("-dependencies") || artifactId.endsWith("-bom");
    }

    private static Optional<VersionSpacePort.BomSnapshot> cloudBom(VersionSpacePort versionSpace,
                                                                   String train) {
        if (train == null || train.isBlank() || "null".equals(train)) {
            return Optional.empty();
        }
        return versionSpace.bom("org.springframework.cloud", "spring-cloud-dependencies", train);
    }

    @SafeVarargs
    private static List<VersionSpacePort.BomSnapshot> presentOf(
            Optional<VersionSpacePort.BomSnapshot>... boms) {
        List<VersionSpacePort.BomSnapshot> present = new ArrayList<>();
        for (Optional<VersionSpacePort.BomSnapshot> bom : boms) {
            bom.ifPresent(present::add);
        }
        return present;
    }

    /** First BOM in the list that manages the coordinate, or null when none does. */
    private static String bomVersion(List<VersionSpacePort.BomSnapshot> boms, String group,
                                     String artifact) {
        for (VersionSpacePort.BomSnapshot bom : boms) {
            String version = bom.entries().stream()
                    .filter(e -> e.groupId().equals(group) && e.artifactId().equals(artifact))
                    .map(VersionSpacePort.BomEntry::version)
                    .findFirst()
                    .orElse(null);
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    /** Materializes a published jar into the run's cache so javap can read it. */
    private static Optional<Path> jar(StageContext context, VersionSpacePort versionSpace,
                                      String group, String artifact, String version) {
        try {
            Optional<byte[]> bytes = versionSpace.fetchArtifactFile(group, artifact, version, null, "jar");
            if (bytes.isEmpty() || bytes.get().length == 0) {
                return Optional.empty();
            }
            // runWorkspace(), not workspaceRoot(): the latter is the parent of every run's
            // directory, so jars written there would outlive the run and escape retention.
            Path dir = context.run().runWorkspace().resolve("api-diff-jars");
            Files.createDirectories(dir);
            Path file = dir.resolve(group + "-" + artifact + "-" + version + ".jar");
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.get().length) {
                Files.write(file, bytes.get());
            }
            return Optional.of(file);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private MigrationFact fact(MigrationFact.Type type, String subject) {
        return new MigrationFact(Ids.knowledgeId(sequence.incrementAndGet()), type, subject);
    }

    /**
     * Merges a fact into the set. When the two channels describe the same subject, the artifact
     * observation upgrades the documentation candidate; when they disagree about the replacement, the
     * result is CONFLICTING rather than a silent winner.
     */
    private void merge(Map<String, MigrationFact> facts, MigrationFact incoming) {
        String key = incoming.subject() + "|" + incoming.type();
        MigrationFact existing = facts.get(key);
        if (existing == null) {
            facts.put(key, incoming);
            return;
        }
        if (existing.to() != null && incoming.to() != null && !existing.to().equals(incoming.to())) {
            existing.conflict("Documentation says the replacement is " + existing.to()
                    + " but artifact evidence says " + incoming.to());
            return;
        }
        existing.documentRefs().forEach(incoming::document);
        incoming.artifactEvidence().forEach(e -> {
            // already present on the incoming fact
        });
        facts.put(key, incoming);
    }

    private static int majorOf(String version) {
        try {
            return Integer.parseInt(version.split("\\.")[0].replaceAll("[^0-9]", ""));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ edge attribution

    /** One edge of the frozen path, reduced to what fact attribution needs. */
    record EdgeSpan(String edgeId, String edgeClass, String from, String to) {
    }

    static List<EdgeSpan> readEdgeSpans(JsonNode migrationPath) {
        List<EdgeSpan> spans = new ArrayList<>();
        if (migrationPath == null) {
            return spans;
        }
        for (JsonNode edge : migrationPath.path("edges")) {
            String from = edge.path("fromVersion").asText(null);
            String to = edge.path("toVersion").asText(null);
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            spans.add(new EdgeSpan(edge.path("edgeId").asText(), edge.path("edgeClass").asText(),
                    from, to));
        }
        return spans;
    }

    /**
     * Narrows a fact to the edges on which the owning artifact's managed version actually moved.
     *
     * <p>A type that disappears between two published jars disappeared on one of the edges that
     * changed that jar's version. It cannot have disappeared on an edge that did not touch it, so
     * those edges are excluded. That is a real narrowing from a sound premise, and it is labelled
     * ARTIFACT_VERSION_WINDOW rather than EDGE_EXACT because it does not identify which one.
     */
    static void narrowToVersionChangeEdges(MigrationFact fact, List<EdgeSpan> spans,
                                           String sourceVersion, String targetVersion) {
        if (spans == null || spans.isEmpty()) {
            fact.validity(sourceVersion, targetVersion, MigrationFact.ValidityPrecision.SPAN_ONLY);
            return;
        }
        String low = spans.get(0).from();
        String high = spans.get(0).to();
        for (EdgeSpan span : spans) {
            if (MigrationFact.compare(span.from(), low) < 0) {
                low = span.from();
            }
            if (MigrationFact.compare(span.to(), high) > 0) {
                high = span.to();
            }
            fact.appliesToEdge(span.edgeId());
        }
        fact.validity(low, high, spans.size() == 1
                ? MigrationFact.ValidityPrecision.EDGE_EXACT
                : MigrationFact.ValidityPrecision.ARTIFACT_VERSION_WINDOW);
    }

    /**
     * Binds the structural boundary facts to the major-boundary edge.
     *
     * <p>The Jakarta relocation and the language baseline are properties of crossing a major, not of
     * the migration as a whole. Leaving them span-scoped made every minor edge believe it was
     * authorized to rewrite namespaces.
     */
    static void scopeStructuralFactsToBoundary(Map<String, MigrationFact> facts,
                                               List<EdgeSpan> spans, String sourceVersion,
                                               String targetVersion) {
        EdgeSpan boundary = spans.stream()
                .filter(s -> "MAJOR_BOUNDARY".equals(s.edgeClass()))
                .findFirst()
                .orElse(null);
        if (boundary == null) {
            return;
        }
        for (MigrationFact fact : facts.values()) {
            boolean structural = "STRUCTURAL_BOUNDARY".equals(fact.detectionRule())
                    || "javax.* to jakarta.*".equals(fact.subject())
                    || (fact.type() == MigrationFact.Type.BASELINE_REQUIREMENT
                            && "java.version".equals(fact.subject()));
            if (structural) {
                fact.validity(boundary.from(), boundary.to(),
                        MigrationFact.ValidityPrecision.EDGE_EXACT)
                        .appliesToEdge(boundary.edgeId());
            }
        }
    }

    /** Maps a Maven coordinate to the component a migration fact is about. */
    static String componentOf(String coordinate) {
        if (coordinate == null || !coordinate.contains(":")) {
            return "spring-boot";
        }
        String[] parts = coordinate.split(":", 2);
        return componentOf(parts[0], parts[1]);
    }

    static String componentOf(String group, String artifact) {
        String g = group == null ? "" : group;
        String a = artifact == null ? "" : artifact;
        if (g.startsWith("org.springframework.boot")) {
            return "spring-boot";
        }
        if (g.startsWith("org.springframework.security")) {
            return "spring-security";
        }
        if (g.startsWith("org.springframework.data")) {
            return "spring-data";
        }
        if (g.startsWith("org.springframework.cloud")) {
            return "spring-cloud";
        }
        if (g.startsWith("org.springframework.batch")) {
            return "spring-batch";
        }
        if (g.startsWith("org.springframework")) {
            return "spring-framework";
        }
        if (g.startsWith("org.hibernate")) {
            return "hibernate";
        }
        if (g.startsWith("com.fasterxml.jackson")) {
            return "jackson";
        }
        if (g.startsWith("jakarta.") || g.startsWith("javax.")) {
            return "jakarta";
        }
        if (g.startsWith("org.apache.tomcat") || a.contains("undertow") || a.contains("jetty")) {
            return "servlet-container";
        }
        return g.isBlank() ? "spring-boot" : g;
    }

    static String componentOfArtifact(String artifact) {
        if (artifact == null || artifact.isBlank()) {
            return "spring-boot";
        }
        return artifact.contains(":") ? componentOf(artifact) : componentOf("", artifact);
    }
}
