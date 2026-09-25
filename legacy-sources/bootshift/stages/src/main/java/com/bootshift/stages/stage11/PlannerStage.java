package com.bootshift.stages.stage11;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.transform.ConfigurationPropertyTransformer;
import com.bootshift.adapters.transform.JakartaNamespaceTransformer;
import com.bootshift.adapters.transform.MavenPomTransformer;
import com.bootshift.adapters.transform.RemovedAnnotationTransformer;
import com.bootshift.adapters.transform.OpenRewriteCoreProvider;
import com.bootshift.adapters.transform.TestFrameworkTransformer;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.policy.ValidationDepth;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.transformation.TransformationPort;
import com.bootshift.stages.stage08.MigrationFact;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 11 - Migration Planner (spec section 22).
 *
 * <p>The Target Resolver said where to go. The Planner says exactly how, and freezes the answer.
 *
 * <p>Three things happen here that nothing else may do later:
 *
 * <ul>
 *   <li>the {@code TransformationCapabilityRegistry} is built from the tools actually present, so a
 *       missing recipe becomes measured residual instead of a silent hole (R31);</li>
 *   <li>validation depth is computed once and frozen into the edge plan (R16);</li>
 *   <li>composite transformations are reconciled against mandatory checkpoints, and a transformation
 *       that would skip one is decomposed, escalated, or blocked (R26).</li>
 * </ul>
 */
public final class PlannerStage implements Stage {

    public static final String OUTPUT_DIR = "11-plan";

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
        return "Plan and freeze how the frozen migration path will actually be executed";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.CHARACTERIZATION_COMPLETE);
    }

    @Override
    public RunState postcondition() {
        return RunState.PLAN_FROZEN;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("06-target/migration-path.json", "08-knowledge/migration-knowledge.json",
                "09-impact/impact-report.json", "10-characterization/characterization-contracts.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("migration-plan.json", "edge-plan.json",
                "transformation-capability-registry.json", "residual-report.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode target = StageSupport.requireUpstream(context, "06-target", "target-state.json",
                "Run: harness resolve-target --target auto");
        JsonNode path = StageSupport.requireUpstream(context, "06-target", "migration-path.json",
                "Run: harness resolve-target --target auto");
        JsonNode knowledge = StageSupport.requireUpstream(context, "08-knowledge",
                "migration-knowledge.json", "Run: harness knowledge");
        JsonNode impact = StageSupport.requireUpstream(context, "09-impact", "impact-report.json",
                "Run: harness impact");
        JsonNode contracts = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-contracts.json");
        JsonNode lifecycle = StageSupport.optionalUpstream(context, "05-compatibility",
                "lifecycle-registry.json");

        // The Spring Cloud train is locked to the Boot line, so each edge needs the train that goes
        // with ITS target line. Using the landing train on a transit checkpoint installs a train from
        // the future onto an older Boot version, and types removed in between simply vanish.
        Map<String, String> cloudTrainByBootLine = new LinkedHashMap<>();
        Map<String, List<Integer>> javaMajorsByBootLine = new LinkedHashMap<>();
        if (lifecycle != null) {
            for (JsonNode release : lifecycle.path("releases")) {
                String line = release.path("line").asText();
                String train = release.path("spring_cloud_train").asText(null);
                if (train != null && !train.isBlank()) {
                    cloudTrainByBootLine.put(line, train);
                }
                List<Integer> majors = new ArrayList<>();
                release.path("supported_java_majors").forEach(n -> majors.add(n.asInt()));
                javaMajorsByBootLine.put(line, majors);
            }
        }

        // Java levels an installed toolchain can actually provide.
        List<Integer> installedJdks = new com.bootshift.adapters.build.ToolchainProbe().discover()
                .stream()
                .map(com.bootshift.adapters.build.ToolchainProbe.Jdk::major)
                .distinct().sorted().toList();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        // ---------------------------------------------------------- capability registry (R31)
        String sourceVersion = target.path("source_version").asText();
        String targetVersion = target.path("landing_version").asText();
        List<TransformationPort> providers = List.of(
                new MavenPomTransformer(),
                new JakartaNamespaceTransformer(),
                new TestFrameworkTransformer(),
                new RemovedAnnotationTransformer(),
                ConfigurationPropertyTransformer.fromRuleFile(generatedRuleFile(context)),
                new OpenRewriteCoreProvider());

        List<TransformationPort.Capability> capabilities = new ArrayList<>();
        for (TransformationPort provider : providers) {
            capabilities.addAll(provider.capabilities(sourceVersion, targetVersion));
        }

        List<TransformationPort.Capability> availableCapabilities = capabilities.stream()
                .filter(c -> "AVAILABLE".equals(c.status()))
                .toList();
        Set<String> handledFactTypes = new LinkedHashSet<>();
        availableCapabilities.forEach(c -> handledFactTypes.addAll(c.handledFactTypes()));

        ObjectNode registryArtifact = Json.obj();
        registryArtifact.put("source_version", sourceVersion);
        registryArtifact.put("target_version", targetVersion);
        registryArtifact.put("capability_count", capabilities.size());
        registryArtifact.put("available_count",
                capabilities.stream().filter(c -> "AVAILABLE".equals(c.status())).count());
        registryArtifact.put("discovery_rule", "Capabilities are probed from the tools actually "
                + "present at planning time; nothing is assumed to exist for an edge (R31)");
        registryArtifact.set("capabilities", Json.toTree(capabilities));
        registryArtifact.set("handled_fact_types", Json.toTree(handledFactTypes));
        writer.write("transformation-capability-registry.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), registryArtifact));

        // ---------------------------------------------------------- edge-scoped knowledge index
        // Every fact carries the interval in which it is in force. A fact is offered to an edge only
        // when that interval intersects the edge, which is what stops a Boot 3.0 boundary fact
        // authorizing the 3.3 to 3.4 edge. Previously every edge received every fact in the run.
        List<EdgeFact> allEdgeFacts = new ArrayList<>();
        for (JsonNode fact : knowledge.path("facts")) {
            allEdgeFacts.add(EdgeFact.of(fact));
        }

        // ---------------------------------------------------------- deterministic coverage
        // Coverage is computed per FACT, not per fact type. A capability that handles JUnit 4
        // rewrites declares API_REMOVED, and counting the whole type as covered made 56 removed APIs
        // - @EnableEurekaClient among them - disappear from the residual while the edge failed to
        // compile. A capability now has to claim the fact's subject, not just its category.
        Map<String, Integer> factsByType = new java.util.TreeMap<>();
        Map<String, List<String>> factIdsByType = new LinkedHashMap<>();
        Map<String, Integer> coveredByType = new java.util.TreeMap<>();
        Map<String, List<String>> uncoveredIdsByType = new LinkedHashMap<>();
        Map<String, List<String>> uncoveredSubjectsByType = new LinkedHashMap<>();
        int verifiedFacts = 0;
        int covered = 0;
        for (JsonNode fact : knowledge.path("facts")) {
            if (!fact.path("authorizes_transformation").asBoolean(false)) {
                continue;
            }
            verifiedFacts++;
            String type = fact.path("type").asText();
            String subject = fact.path("subject").asText(null);
            factsByType.merge(type, 1, Integer::sum);
            factIdsByType.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(fact.path("knowledge_id").asText());
            boolean handled = availableCapabilities.stream().anyMatch(c -> c.covers(type, subject));
            if (handled) {
                covered++;
                coveredByType.merge(type, 1, Integer::sum);
            } else {
                uncoveredIdsByType.computeIfAbsent(type, k -> new ArrayList<>())
                        .add(fact.path("knowledge_id").asText());
                List<String> subjects = uncoveredSubjectsByType
                        .computeIfAbsent(type, k -> new ArrayList<>());
                if (subject != null && subjects.size() < 25) {
                    subjects.add(subject);
                }
            }
        }
        List<ObjectNode> residual = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : uncoveredIdsByType.entrySet()) {
            ObjectNode item = Json.obj();
            item.put("fact_type", entry.getKey());
            item.put("fact_count", entry.getValue().size());
            item.set("knowledge_ids", Json.toTree(entry.getValue()));
            item.set("example_subjects", Json.toTree(uncoveredSubjectsByType.get(entry.getKey())));
            item.put("reason", handledFactTypes.contains(entry.getKey())
                    ? "A capability declares this fact type but none claims these subjects"
                    : "No AVAILABLE deterministic capability handles this fact type");
            item.put("consequence", "Counted as residual, which raises the planned validation "
                    + "depth for affected edges");
            residual.add(item);
        }
        double deterministicCoverage = verifiedFacts == 0 ? 1.0
                : Math.round((double) covered / verifiedFacts * 10000.0) / 10000.0;

        // A single coverage number over this fact set is dominated by property renames, which the
        // generated rules cover trivially. Reported alone it reads as "the migration is 99.8%
        // handled" when the four API facts that decide whether the edge compiles may all be
        // uncovered. Report the breakdown, and say what the number does not measure.
        ObjectNode coverageByType = Json.obj();
        for (Map.Entry<String, Integer> entry : factsByType.entrySet()) {
            int total = entry.getValue();
            int hit = coveredByType.getOrDefault(entry.getKey(), 0);
            ObjectNode row = Json.obj();
            row.put("fact_count", total);
            row.put("covered", hit);
            row.put("uncovered", total - hit);
            row.put("type_claimed_by_a_capability", handledFactTypes.contains(entry.getKey()));
            coverageByType.set(entry.getKey(), row);
        }

        ObjectNode residualArtifact = Json.obj();
        residualArtifact.put("verified_fact_count", verifiedFacts);
        residualArtifact.put("deterministically_covered", covered);
        residualArtifact.put("deterministic_coverage", deterministicCoverage);
        residualArtifact.set("coverage_by_fact_type", coverageByType);
        residualArtifact.put("metric_scope", "Deterministic coverage is the fraction of VERIFIED "
                + "facts for which an AVAILABLE transformer exists. It measures rule availability "
                + "for the facts the harness KNOWS about. It does not measure whether the fact set "
                + "is complete: a required change nobody discovered is not counted as uncovered, "
                + "it is not counted at all. Knowledge completeness is reported separately by the "
                + "artifact channel's api_diff coverage.");
        residualArtifact.put("policy_no_escalation_threshold",
                context.policy().residualNoEscalationThreshold());
        residualArtifact.put("policy_one_level_threshold", context.policy().residualOneLevelThreshold());
        residualArtifact.put("note", "Residual is not a failure. It is exactly what the bounded "
                + "compile-repair and differential layers exist to manage.");
        residualArtifact.set("residual", Json.toTree(residual));
        writer.write("residual-report.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), residualArtifact));

        // ---------------------------------------------------------- impact index, keyed by fact
        // An impact finding exists because a migration fact points at a symbol. Selecting impacts
        // per edge therefore means selecting the impacts whose originating fact is in force on that
        // edge - not copying every finding in the run into every edge, which is what made a patch
        // edge claim authority over the whole repository.
        List<ImpactFinding> allImpacts = new ArrayList<>();
        for (JsonNode finding : impact.path("findings")) {
            if ("UNAFFECTED_WITHIN_OBSERVED_COVERAGE".equals(finding.path("classification").asText())) {
                continue;
            }
            allImpacts.add(ImpactFinding.of(finding));
        }
        Set<String> requiredDimensions = new LinkedHashSet<>();
        Set<String> allImpactedFileIds = new LinkedHashSet<>();
        Set<String> impactIds = new LinkedHashSet<>();
        boolean anyHighRisk = false;
        for (ImpactFinding finding : allImpacts) {
            impactIds.add(finding.impactId());
            requiredDimensions.addAll(finding.dimensions());
            if (finding.fileId() != null) {
                allImpactedFileIds.add(finding.fileId());
            }
            anyHighRisk = anyHighRisk || finding.highRisk();
        }
        boolean impactRecallBelowFloor = impact.path("accuracy").path("evaluated").asBoolean(false)
                && impact.path("accuracy").path("recall").asDouble(1.0)
                < context.policy().impactRecallFloor();

        JsonNode buildModelNode = StageSupport.optionalUpstream(context, "02-build", "build-model.json");
        String currentJavaLevel = buildModelNode == null ? "17"
                : buildModelNode.path("frameworks").path("java").asText("17");

        // Test sources, needed as scope for the preparatory test-infrastructure edge.
        Set<String> testFileIds = new LinkedHashSet<>();
        com.bootshift.core.identity.FileRegistry registry =
                com.bootshift.stages.EdgeSupport.loadRegistry(context);
        registry.active().stream()
                .filter(r -> r.getRole() == com.bootshift.core.identity.FileRole.JAVA_TEST)
                .forEach(r -> testFileIds.add(r.getFileId()));

        // ---------------------------------------------------------- per-edge plan
        List<ObjectNode> edgePlans = new ArrayList<>();
        List<String> reconciliationDecisions = new ArrayList<>();
        int index = 0;
        for (JsonNode edge : path.path("edges")) {
            index++;
            ObjectNode plan = planEdge(context, edge, index, providers, capabilities,
                    availableCapabilities, allEdgeFacts, allImpacts, deterministicCoverage,
                    impactRecallBelowFloor, contracts, reconciliationDecisions, testFileIds,
                    cloudTrainByBootLine, javaMajorsByBootLine, installedJdks, currentJavaLevel,
                    sourceVersion, targetVersion);
            edgePlans.add(plan);
        }

        // ---------------------------------------------------------- checkpoint reconciliation (R26)
        boolean blocked = reconciliationDecisions.stream().anyMatch(d -> d.startsWith("BLOCK"));
        if (blocked) {
            ObjectNode blockedPlan = Json.obj();
            blockedPlan.set("edges", Json.toTree(edgePlans));
            blockedPlan.set("reconciliation", Json.toTree(reconciliationDecisions));
            writer.write("edge-plan.json",
                    StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), blockedPlan));
            StageSupport.publish(context, writer);
            throw HarnessException.block("A mandatory migration checkpoint would be skipped and policy "
                    + "forbids collapsing it: " + reconciliationDecisions);
        }

        ObjectNode edgePlanArtifact = Json.obj();
        edgePlanArtifact.put("edge_count", edgePlans.size());
        edgePlanArtifact.set("edges", Json.toTree(edgePlans));
        edgePlanArtifact.set("reconciliation_decisions", Json.toTree(reconciliationDecisions));
        ObjectNode edgeArtifact = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), edgePlanArtifact);
        StageSupport.validate(context, writer, "migration-plan/edge-plan.schema.json",
                "edge-plan.json", edgeArtifact);
        writer.write("edge-plan.json", edgeArtifact);

        ObjectNode migrationPlan = Json.obj();
        migrationPlan.put("source_version", sourceVersion);
        migrationPlan.put("target_version", targetVersion);
        migrationPlan.put("edge_count", edgePlans.size());
        migrationPlan.put("deterministic_coverage", deterministicCoverage);
        migrationPlan.put("verified_facts", verifiedFacts);
        migrationPlan.put("impacted_files", allImpactedFileIds.size());
        migrationPlan.set("required_differential_dimensions", Json.toTree(requiredDimensions));
        migrationPlan.put("frozen", true);
        migrationPlan.put("freeze_rule", "Execution reads the planned depth; it never invents its own");
        migrationPlan.set("edge_summary", Json.toTree(edgePlans.stream().map(e -> Map.of(
                "edge_id", e.path("edge_id").asText(),
                "edge_class", e.path("edge_class").asText(),
                "validation_depth", e.path("frozen_validation_depth").asText(),
                "mandatory", e.path("mandatory_checkpoint").asBoolean(),
                "verified_facts", e.path("verified_fact_count").asInt(),
                "impacted_files", e.path("affected_file_count").asInt(),
                "deterministic_coverage", e.path("deterministic_coverage").asDouble(),
                "edge_java", e.path("edge_java").asText())).toList()));
        migrationPlan.put("per_edge_scoping", "Every edge carries its own facts, impacts, files, "
                + "dimensions, residual, toolchain and scenarios. No value is shared between edges.");
        envelope.stat("deterministic_coverage", deterministicCoverage)
                .stat("edges", edgePlans.size())
                .stat("residual_fact_types", residual.size());
        ObjectNode planArtifact = StageSupport.compose(envelope, migrationPlan);
        StageSupport.validate(context, writer, "migration-plan/migration-plan.schema.json",
                "migration-plan.json", planArtifact);
        writer.write("migration-plan.json", planArtifact);

        StageSupport.toEvidence(context, "migration-plan", planArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Plan artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        // Seed the execution index from the frozen plan so every planned edge is visible to
        // resumption and to final evidence, including edges that never run.
        com.bootshift.stages.EdgeIndex.open(context).seedFromPlan(edgeArtifact).persist();
        context.stateMachine().transition(RunState.PLAN_FROZEN, edgePlans.size() + " edge(s) frozen");
        context.runStateStore().updateState(context.run().runId(), RunState.PLAN_FROZEN, "plan frozen");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>(reconciliationDecisions);
        if (!residual.isEmpty()) {
            messages.add(residual.size() + " fact type(s) have no deterministic capability; "
                    + "deterministic coverage is " + deterministicCoverage);
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                edgePlans.size() + " edge(s) frozen, deterministic coverage "
                        + deterministicCoverage + ", " + requiredDimensions.size()
                        + " differential dimension(s) required",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ edge planning

    /**
     * One edge of the frozen plan.
     *
     * <p>Everything here is computed for THIS edge: the facts in force on it, the impacts those
     * facts produced, the files those impacts name, the validation dimensions they require, the
     * risk, the residual, the toolchain and the scenarios. The previous implementation computed all
     * of these once for the whole migration and copied the same values into every edge, so a patch
     * edge claimed authority over every impacted file in the repository and a coverage figure that
     * described a different edge entirely.
     */
    private ObjectNode planEdge(StageContext context, JsonNode edge, int index,
                                List<TransformationPort> providers,
                                List<TransformationPort.Capability> capabilities,
                                List<TransformationPort.Capability> availableCapabilities,
                                List<EdgeFact> allEdgeFacts, List<ImpactFinding> allImpacts,
                                double runCoverage, boolean impactRecallBelowFloor,
                                JsonNode contracts, List<String> reconciliationDecisions,
                                Set<String> testFileIds, Map<String, String> cloudTrainByBootLine,
                                Map<String, List<Integer>> javaMajorsByBootLine,
                                List<Integer> installedJdks, String currentJava,
                                String sourceVersion, String targetVersion) {
        ObjectNode plan = Json.obj();
        String edgeId = edge.path("edgeId").asText("EDGE-" + index);
        String edgeClass = edge.path("edgeClass").asText("MINOR");
        boolean landing = edge.path("landing").asBoolean(false);
        boolean mandatory = edge.path("mandatory").asBoolean(false);
        String edgeFrom = edge.path("fromVersion").asText();
        String edgeTo = edge.path("toVersion").asText();

        plan.put("edge_id", edgeId);
        plan.put("edge_class", edgeClass);
        plan.put("source_state", edgeFrom);
        plan.put("target_state", edgeTo);
        plan.put("landing", landing);
        plan.put("transit", !landing);
        plan.put("mandatory_checkpoint", mandatory);
        plan.put("rationale", edge.path("rationale").asText());
        plan.put("exists_because", edge.path("existsBecause").asText(null));
        plan.set("path_supporting_evidence", edge.path("supportingEvidence"));

        // ---- facts in force on THIS edge ---------------------------------------------------------
        List<EdgeFact> edgeFacts = new ArrayList<>();
        List<EdgeFact> edgeVerifiedFacts = new ArrayList<>();
        int scopedByAttribution = 0;
        int scopedByInterval = 0;
        for (EdgeFact fact : allEdgeFacts) {
            if (!fact.appliesTo(edgeId, edgeFrom, edgeTo)) {
                continue;
            }
            if ("DECLARED_EDGE_ATTRIBUTION".equals(fact.scopingChannel())) {
                scopedByAttribution++;
            } else {
                scopedByInterval++;
            }
            edgeFacts.add(fact);
            if (fact.authorizes()) {
                edgeVerifiedFacts.add(fact);
            }
        }
        // How the narrowing was decided, so a reader can tell a genuinely small edge from a scoping
        // failure. An edge whose facts all arrived by interval intersection has no direct per-edge
        // artifact evidence behind it, and that is worth seeing in the plan.
        ObjectNode scoping = Json.obj();
        scoping.put("facts_considered", allEdgeFacts.size());
        scoping.put("facts_in_force", edgeFacts.size());
        scoping.put("scoped_by_declared_edge_attribution", scopedByAttribution);
        scoping.put("scoped_by_validity_interval", scopedByInterval);
        scoping.put("rule", "A fact attributed to specific edges applies to those edges only. A fact "
                + "with no attribution falls back to intersecting its validity window with the edge "
                + "span. Attribution is evidence of absence as well as of presence.");
        plan.set("fact_scoping", scoping);
        Set<String> edgeKnowledgeIds = new LinkedHashSet<>();
        edgeVerifiedFacts.forEach(f -> edgeKnowledgeIds.add(f.knowledgeId()));
        Map<String, Integer> factsByComponent = new java.util.TreeMap<>();
        edgeVerifiedFacts.forEach(f -> factsByComponent.merge(f.component(), 1, Integer::sum));

        // ---- impacts produced by those facts ------------------------------------------------------
        List<ImpactFinding> edgeImpacts = new ArrayList<>();
        for (ImpactFinding finding : allImpacts) {
            if (finding.knowledgeId() != null && edgeKnowledgeIds.contains(finding.knowledgeId())) {
                edgeImpacts.add(finding);
            }
        }
        Set<String> edgeImpactIds = new LinkedHashSet<>();
        Set<String> edgeFileIds = new LinkedHashSet<>();
        Set<String> edgeSymbols = new LinkedHashSet<>();
        Set<String> edgeDimensions = new LinkedHashSet<>();
        boolean edgeHighRisk = false;
        for (ImpactFinding finding : edgeImpacts) {
            edgeImpactIds.add(finding.impactId());
            edgeDimensions.addAll(finding.dimensions());
            if (finding.fileId() != null) {
                edgeFileIds.add(finding.fileId());
            }
            if (finding.symbol() != null) {
                edgeSymbols.add(finding.symbol());
            }
            edgeHighRisk = edgeHighRisk || finding.highRisk();
        }

        plan.put("knowledge_fact_count", edgeFacts.size());
        plan.put("verified_fact_count", edgeVerifiedFacts.size());
        plan.set("facts_by_component", Json.toTree(factsByComponent));
        plan.set("knowledge_refs", Json.toTree(edgeKnowledgeIds));
        plan.set("impact_refs", Json.toTree(edgeImpactIds));
        plan.set("affected_symbols", Json.toTree(edgeSymbols));
        plan.put("risk", edgeHighRisk ? "HIGH" : (edgeImpacts.isEmpty() ? "LOW" : "MEDIUM"));
        plan.put("scoping_rule", "Facts, impacts, files and dimensions are selected by intersecting "
                + "each fact's validity interval with this edge. Nothing is inherited from the "
                + "migration as a whole.");

        // ---- the Spring Cloud train for THIS edge, not for the landing target --------------------
        String edgeBootLine = lineOf(edgeTo);
        String edgeCloudTrain = cloudTrainByBootLine.get(edgeBootLine);
        plan.put("spring_cloud_train", edgeCloudTrain);

        // ---- the toolchain for THIS edge ---------------------------------------------------------
        List<Integer> supportedHere = javaMajorsByBootLine.getOrDefault(edgeBootLine, List.of());
        int sourceJavaLevel = parseJavaLevel(currentJava);
        com.bootshift.adapters.build.JavaTargetSelector.Preference preference;
        try {
            preference = com.bootshift.adapters.build.JavaTargetSelector.Preference
                    .valueOf(context.policy().javaTargetPreference());
        } catch (IllegalArgumentException e) {
            preference = com.bootshift.adapters.build.JavaTargetSelector.Preference.LTS_PREFERRED;
        }
        List<com.bootshift.adapters.build.ToolchainProbe.Jdk> jdks =
                new com.bootshift.adapters.build.ToolchainProbe().discover();
        com.bootshift.adapters.build.JavaTargetSelector.Selection javaSelection =
                new com.bootshift.adapters.build.JavaTargetSelector()
                        .select(supportedHere, sourceJavaLevel, jdks, preference);
        plan.put("current_java", currentJava);
        plan.put("edge_java", String.valueOf(javaSelection.major()));
        plan.put("edge_java_version", javaSelection.version());
        plan.put("edge_java_vendor", javaSelection.vendor());
        plan.put("edge_java_home", javaSelection.home());
        plan.put("edge_java_selection_reason", javaSelection.selectionReason());
        plan.set("edge_java_supporting_evidence", Json.toTree(javaSelection.supportingEvidence()));
        plan.put("edge_java_resolved", javaSelection.resolved());
        plan.put("edge_java_is_lts", javaSelection.lts());
        plan.put("spring_cloud_train_note", edgeCloudTrain == null
                ? "No GA Spring Cloud train targets Boot " + edgeBootLine + "; the managed-version "
                  + "transformation is omitted for this edge rather than installing a mismatched train"
                : "Spring Cloud " + edgeCloudTrain + " is the train published for Boot " + edgeBootLine);

        // ---- ordered transformations, each bound to the capability that implements it ------------
        ArrayNode transformations = Json.arr();
        List<String> uncoveredRecipes = new ArrayList<>();
        boolean openRewriteJava = providers.stream()
                .filter(pr -> pr instanceof OpenRewriteCoreProvider)
                .map(pr -> (OpenRewriteCoreProvider) pr)
                .anyMatch(pr -> pr.coreAvailable() && pr.javaModuleAvailable()
                        && !pr.forbiddenEstatePresent());
        for (ScheduledRecipe scheduled : scheduleFor(edgeClass, edge, openRewriteJava)) {
            String recipeId = scheduled.recipeId();
            if (MavenPomTransformer.RECIPE_MANAGED_VERSION.equals(recipeId) && edgeCloudTrain == null) {
                continue;
            }
            String providerName = providerFor(recipeId);
            TransformationPort owningProvider = providers.stream()
                    .filter(pr -> pr.handles(recipeId))
                    .findFirst()
                    .orElse(null);
            TransformationPort.Capability capability = owningProvider == null ? null
                    : owningProvider.capabilityFor(recipeId, edgeFrom, edgeTo).orElse(null);
            ObjectNode transformation = Json.obj();
            transformation.put("recipe_id", recipeId);
            transformation.put("preferred_transformer", providerName);
            transformation.put("fallback_strategy", fallbackFor(recipeId));
            transformation.put("capability_id", capability == null ? null : capability.capabilityId());
            transformation.put("capability_provider", capability == null ? null : capability.provider());
            transformation.put("capability_status",
                    capability == null ? "NO_CAPABILITY_CLAIMS_THIS_RECIPE" : capability.status());
            transformation.put("capability_license", capability == null ? null : capability.licenseSpdx());
            transformation.put("deterministic", capability == null || capability.deterministic());
            // Parameters come from the plan, derived from verified facts, rather than being
            // improvised by the transformation stage at apply time.
            transformation.set("parameters", Json.toTree(scheduled.parameters()));
            transformation.put("why", scheduled.why());
            transformations.add(transformation);
            if (capability == null || !"AVAILABLE".equals(capability.status())) {
                uncoveredRecipes.add(recipeId);
            }
        }
        plan.set("ordered_transformations", transformations);
        plan.set("recipes_without_available_capability", Json.toTree(uncoveredRecipes));

        // ---- deterministic coverage for THIS edge -------------------------------------------------
        int edgeCovered = 0;
        Map<String, List<String>> edgeUncoveredByType = new LinkedHashMap<>();
        for (EdgeFact fact : edgeVerifiedFacts) {
            boolean handled = availableCapabilities.stream()
                    .anyMatch(c -> c.covers(fact.type(), fact.subject()));
            if (handled) {
                edgeCovered++;
            } else {
                edgeUncoveredByType.computeIfAbsent(fact.type(), k -> new ArrayList<>())
                        .add(fact.knowledgeId());
            }
        }
        double edgeCoverage = edgeVerifiedFacts.isEmpty() ? 1.0
                : Math.round((double) edgeCovered / edgeVerifiedFacts.size() * 10000.0) / 10000.0;
        plan.put("deterministic_coverage", edgeCoverage);
        plan.put("deterministically_covered_facts", edgeCovered);
        ObjectNode edgeResidual = Json.obj();
        edgeUncoveredByType.forEach((type, ids) -> {
            ObjectNode row = Json.obj();
            row.put("uncovered", ids.size());
            row.set("knowledge_ids", Json.toTree(ids.size() > 25 ? ids.subList(0, 25) : ids));
            edgeResidual.set(type, row);
        });
        plan.set("residual_by_fact_type", edgeResidual);
        plan.put("expected_residual", 1.0 - edgeCoverage);

        // ---- affected scope ------------------------------------------------------------------------
        //
        // Impact findings name the files a verified migration fact points at, for THIS edge. A
        // preparatory test-infrastructure edge exists precisely to change files no framework fact
        // mentions, so it additionally owns the test sources; without this the gateway would
        // correctly reject every JUnit 4 rewrite as out of scope.
        Set<String> edgeScope = new LinkedHashSet<>(edgeFileIds);
        if ("PREPARATORY".equals(edgeClass)) {
            edgeScope.addAll(testFileIds);
        }
        plan.set("affected_file_ids", Json.toTree(edgeScope));
        plan.put("affected_file_count", edgeScope.size());
        plan.put("scope_note", "PREPARATORY".equals(edgeClass)
                ? "Impact-derived scope for this edge plus every test source, because this edge "
                  + "migrates test infrastructure rather than application behaviour"
                : "Impact-derived scope for this edge plus build descriptors");

        // ---- composite transformation reconciliation (R26) ---------------------------------------
        List<TransformationPort.Capability> spanning = capabilities.stream()
                .filter(c -> "AVAILABLE".equals(c.status()))
                .filter(c -> "MULTI_EDGE".equals(c.checkpointSpan()))
                .toList();
        if (!spanning.isEmpty()) {
            if (context.policy().allowCheckpointCollapse()) {
                plan.put("checkpoint_reconciliation", "COLLAPSE_WITH_ESCALATED_VALIDATION");
                reconciliationDecisions.add("COLLAPSE_WITH_ESCALATED_VALIDATION on " + edgeId
                        + ": composite capabilities " + spanning.stream()
                        .map(TransformationPort.Capability::capabilityId).toList()
                        + " span multiple checkpoints; depth raised to the maximum required");
            } else {
                plan.put("checkpoint_reconciliation", "BLOCK");
                reconciliationDecisions.add("BLOCK on " + edgeId
                        + ": a composite transformation would skip a mandatory checkpoint and policy "
                        + "forbids collapsing");
            }
        } else {
            plan.put("checkpoint_reconciliation", "DECOMPOSED");
        }

        // ---- validation depth: computed once for THIS edge, then frozen --------------------------
        ValidationDepth classDepth = switch (edgeClass) {
            case "PATCH" -> ValidationDepth.BUILD_AND_TESTS;
            case "MINOR" -> ValidationDepth.BUILD_TESTS_RUNTIME;
            case "PREPARATORY" -> ValidationDepth.IMPACTED_DIFFERENTIAL;
            case "MAJOR_BOUNDARY", "LANDING" -> ValidationDepth.FULL_DIFFERENTIAL;
            default -> ValidationDepth.BUILD_TESTS_RUNTIME;
        };
        ValidationDepth residualDepth =
                context.policy().escalateForResidual(classDepth, edgeCoverage);
        ValidationDepth impactDepth = edgeDimensions.isEmpty()
                ? ValidationDepth.BUILD_AND_TESTS
                : (edgeHighRisk ? ValidationDepth.FULL_DIFFERENTIAL : ValidationDepth.IMPACTED_DIFFERENTIAL);
        ValidationDepth policyDepth = impactRecallBelowFloor
                ? ValidationDepth.FULL_DIFFERENTIAL : ValidationDepth.BUILD_ONLY;
        if ("COLLAPSE_WITH_ESCALATED_VALIDATION".equals(plan.path("checkpoint_reconciliation").asText())) {
            policyDepth = ValidationDepth.FULL_DIFFERENTIAL;
        }
        ValidationDepth depth = ValidationDepth.max(classDepth, residualDepth, impactDepth, policyDepth);

        ObjectNode depthCalculation = Json.obj();
        depthCalculation.put("class_depth", classDepth.name());
        depthCalculation.put("residual_depth", residualDepth.name());
        depthCalculation.put("impact_required_depth", impactDepth.name());
        depthCalculation.put("policy_required_depth", policyDepth.name());
        depthCalculation.put("edge_deterministic_coverage", edgeCoverage);
        depthCalculation.put("formula", "MAX(class, residual, impact, policy) computed from THIS "
                + "edge's own residual and impact set");
        plan.set("validation_depth_calculation", depthCalculation);
        plan.put("frozen_validation_depth", depth.name());

        // ---- obligations derived from the frozen depth -------------------------------------------
        plan.put("tests_required", depth.requiresTests());
        plan.put("runtime_required", depth.requiresRuntime());
        plan.put("differential_required", depth.requiresDifferential());
        plan.set("differential_dimensions",
                Json.toTree(depth.requiresDifferential() ? edgeDimensions : Set.<String>of()));
        plan.set("required_validation_dimensions", Json.toTree(edgeDimensions));

        // ---- characterization scenarios that belong to THIS edge ---------------------------------
        ArrayNode contractRefs = Json.arr();
        int criticalScenarios = 0;
        int frozenScenarios = 0;
        int unobservableScenarios = 0;
        if (contracts != null) {
            for (JsonNode contract : contracts.path("contracts")) {
                String dimension = contract.path("dimension").asText();
                String impactRef = contract.path("impact_id").asText(null);
                boolean belongs = edgeDimensions.contains(dimension)
                        && (impactRef == null || impactRef.isBlank() || edgeImpactIds.contains(impactRef));
                if (!belongs) {
                    continue;
                }
                contractRefs.add(contract.path("scenario_id").asText());
                criticalScenarios++;
                String state = contract.path("state").asText();
                if ("FROZEN".equals(state) || "MAPPED_TO_EXISTING_TEST".equals(state)) {
                    frozenScenarios++;
                } else if ("UNOBSERVABLE".equals(state)) {
                    unobservableScenarios++;
                }
            }
        }
        plan.set("characterization_refs", contractRefs);
        plan.put("characterization_required", criticalScenarios);
        plan.put("characterization_protected", frozenScenarios);
        plan.put("characterization_unobservable", unobservableScenarios);
        plan.put("characterization_awaiting_old",
                Math.max(0, criticalScenarios - frozenScenarios - unobservableScenarios));

        plan.put("approval_required", "MAJOR_BOUNDARY".equals(edgeClass) || edgeHighRisk);
        plan.set("checkpoint_requirements", Json.toTree(List.of(
                "mig/<run>/" + edgeId + "/start",
                "mig/<run>/" + edgeId + "/transformed",
                "mig/<run>/" + edgeId + "/compiled",
                "mig/<run>/" + edgeId + "/graph-verified",
                "mig/<run>/" + edgeId + "/tested",
                "mig/<run>/" + edgeId + "/runtime-validated",
                "mig/<run>/" + edgeId + "/differential-validated")));
        return plan;
    }

    // ------------------------------------------------------------------ edge-scoped views

    /**
     * A migration fact reduced to what edge scoping needs, with its validity interval.
     *
     * <p>Reading the interval here rather than in the planner body keeps the intersection rule in one
     * place: {@link MigrationFact#appliesToEdge(String, String)} is the definition, and this record
     * simply carries the serialized form of it.
     */
    public record EdgeFact(String knowledgeId, String type, String subject, String component,
                           String validFrom, String validTo, String validityPrecision,
                           boolean authorizes, List<String> declaredEdges) {

        public static EdgeFact of(JsonNode fact) {
            List<String> edges = new ArrayList<>();
            fact.path("applies_to_edges").forEach(n -> edges.add(n.asText()));
            return new EdgeFact(fact.path("knowledge_id").asText(),
                    fact.path("type").asText(), fact.path("subject").asText(null),
                    fact.path("component").asText("spring-boot"),
                    fact.path("valid_from").asText(null), fact.path("valid_to").asText(null),
                    fact.path("validity_precision").asText("SPAN_ONLY"),
                    fact.path("authorizes_transformation").asBoolean(false), edges);
        }

        /**
         * True when this fact is in force on the given edge.
         *
         * <p>Two channels, in priority order.
         *
         * <ul>
         *   <li><b>Declared attribution.</b> The knowledge engine resolved the BOM at each end of
         *       every edge and recorded which edges a coordinate actually moved across. That is
         *       direct evidence about a specific edge, so when it exists it decides - and it decides
         *       both ways. A fact attributed to {@code EDGE-3-MAJOR-3} and not to
         *       {@code EDGE-2-PATCH} is <em>absent</em> from the patch edge; it is not merely
         *       unproven there.</li>
         *   <li><b>Interval intersection.</b> A fact with no attribution falls back to its validity
         *       window, intersected with the edge span.</li>
         * </ul>
         *
         * <p>This used to return {@code true} whenever attribution existed, without ever comparing it
         * to the edge - the edge id was not even a parameter. Every edge therefore received every
         * attributed fact, which is why all eight edges reported an identical fact count and an
         * identical deterministic coverage: the plan looked per-edge and was not.
         */
        public boolean appliesTo(String edgeId, String edgeFrom, String edgeTo) {
            if (!declaredEdges.isEmpty()) {
                return edgeId != null && declaredEdges.contains(edgeId);
            }
            if (validFrom == null || validTo == null) {
                return false;
            }
            return MigrationFact.compare(validFrom, edgeTo) <= 0
                    && MigrationFact.compare(validTo, edgeFrom) > 0;
        }

        /** Which channel decided, recorded on the edge plan so the narrowing is auditable. */
        public String scopingChannel() {
            return declaredEdges.isEmpty() ? "VALIDITY_INTERVAL_INTERSECTION" : "DECLARED_EDGE_ATTRIBUTION";
        }
    }

    /** An impact finding reduced to what edge scoping needs. */
    record ImpactFinding(String impactId, String knowledgeId, String fileId, String symbol,
                         String module, boolean highRisk, List<String> dimensions) {

        static ImpactFinding of(JsonNode finding) {
            List<String> dimensions = new ArrayList<>();
            finding.path("required_validation_dimensions").forEach(d -> dimensions.add(d.asText()));
            String fileId = finding.path("file_id").asText(null);
            return new ImpactFinding(finding.path("impact_id").asText(),
                    finding.path("knowledge_id").asText(null),
                    fileId == null || fileId.isBlank() ? null : fileId,
                    finding.path("symbol_id").asText(finding.path("node_id").asText(null)),
                    finding.path("module").asText("."),
                    "HIGH".equals(finding.path("risk").asText()), dimensions);
        }
    }

    /** One scheduled transformation: which recipe, with which parameters, and why. */
    public record ScheduledRecipe(String recipeId, Map<String, String> parameters, String why) {

        public static ScheduledRecipe of(String recipeId, String why) {
            return new ScheduledRecipe(recipeId, Map.of(), why);
        }
    }

    /**
     * The ordered transformation schedule for an edge.
     *
     * <p>Where OpenRewrite's Java module is available, the namespace relocation is scheduled through
     * it rather than through the harness's own textual transformer. The difference is not cosmetic: a
     * textual rewrite matches the token wherever it appears, including inside comments and string
     * literals, whereas ChangePackage operates on a parsed model and rewrites declarations, imports
     * and type references only.
     *
     * <p>One recipe instance is scheduled per relocated package, each carrying its own parameters,
     * because that is the shape OpenRewrite's ChangePackage takes.
     */
    public static List<ScheduledRecipe> scheduleFor(String edgeClass, JsonNode edge,
                                                    boolean openRewriteJavaAvailable) {
        List<ScheduledRecipe> schedule = new ArrayList<>();
        switch (edgeClass) {
            case "PREPARATORY" -> schedule.add(ScheduledRecipe.of(
                    TestFrameworkTransformer.RECIPE_JUNIT4_TO_JUPITER,
                    "Test infrastructure moves before any framework change so pass/fail/skip "
                            + "semantics are proven to survive on their own"));
            case "MAJOR_BOUNDARY" -> {
                schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_PARENT_VERSION,
                        "The parent POM version is what actually changes which framework the module "
                                + "compiles against"));
                schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_PROPERTY,
                        "The declared Java level moves with the major boundary, not before it"));
                schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_MANAGED_VERSION,
                        "The Spring Cloud train is version-locked to the Boot line"));
                if (openRewriteJavaAvailable) {
                    for (String relocated : JakartaNamespaceTransformer.RELOCATED) {
                        Map<String, String> parameters = new LinkedHashMap<>();
                        parameters.put("oldPackageName", relocated);
                        parameters.put("newPackageName",
                                relocated.replaceFirst("^javax\\.", "jakarta."));
                        parameters.put("recursive", "true");
                        schedule.add(new ScheduledRecipe(
                                OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE, parameters,
                                "Jakarta EE relocated " + relocated + ". Applied through "
                                        + "OpenRewrite's type-aware ChangePackage so comments, "
                                        + "string literals and JDK javax packages are untouched."));
                    }
                } else {
                    schedule.add(ScheduledRecipe.of(JakartaNamespaceTransformer.RECIPE,
                            "Jakarta EE namespace relocation. OpenRewrite's Java module is not "
                                    + "available, so the harness's own import-scoped transformer is "
                                    + "used and the reduced precision is recorded."));
                }
                schedule.add(ScheduledRecipe.of(RemovedAnnotationTransformer.RECIPE_REMOVE_ANNOTATION,
                        "The Spring Cloud train moves with the Boot major, and the train is where "
                                + "the opt-in annotations were deleted"));
                schedule.add(ScheduledRecipe.of(ConfigurationPropertyTransformer.RECIPE,
                        "Configuration properties renamed at this boundary, from the official "
                                + "deprecation metadata"));
            }
            case "PATCH", "MINOR", "LANDING" -> {
                schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_PARENT_VERSION,
                        "Move the parent POM to this edge's target version"));
                schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_MANAGED_VERSION,
                        "Move the Spring Cloud train to the one published for this Boot line"));
                schedule.add(ScheduledRecipe.of(RemovedAnnotationTransformer.RECIPE_REMOVE_ANNOTATION,
                        "Remove annotations deleted at this version"));
                schedule.add(ScheduledRecipe.of(ConfigurationPropertyTransformer.RECIPE,
                        "Apply property renames deprecated at this version"));
            }
            default -> schedule.add(ScheduledRecipe.of(MavenPomTransformer.RECIPE_PARENT_VERSION,
                    "Default: move the parent POM"));
        }
        return schedule;
    }

    /** The ordered recipe list for an edge class. Deliberately explicit rather than discovered. */
    public static List<String> recipesFor(String edgeClass, JsonNode edge) {
        return switch (edgeClass) {
            case "PREPARATORY" -> List.of(
                    TestFrameworkTransformer.RECIPE_JUNIT4_TO_JUPITER);
            case "MAJOR_BOUNDARY" -> List.of(
                    MavenPomTransformer.RECIPE_PARENT_VERSION,
                    MavenPomTransformer.RECIPE_PROPERTY,
                    MavenPomTransformer.RECIPE_MANAGED_VERSION,
                    JakartaNamespaceTransformer.RECIPE,
                    // The Spring Cloud train moves with the Boot major, and the train is where the
                    // opt-in annotations were deleted. Ordered after the namespace rewrite so a
                    // single file is only ever rewritten by one recipe at a time.
                    RemovedAnnotationTransformer.RECIPE_REMOVE_ANNOTATION,
                    ConfigurationPropertyTransformer.RECIPE);
            case "PATCH", "MINOR", "LANDING" -> List.of(
                    MavenPomTransformer.RECIPE_PARENT_VERSION,
                    MavenPomTransformer.RECIPE_MANAGED_VERSION,
                    RemovedAnnotationTransformer.RECIPE_REMOVE_ANNOTATION,
                    ConfigurationPropertyTransformer.RECIPE);
            default -> List.of(MavenPomTransformer.RECIPE_PARENT_VERSION);
        };
    }

    static int parseJavaLevel(String value) {
        if (value == null || value.isBlank()) {
            return 17;
        }
        String cleaned = value.startsWith("1.") ? value.substring(2) : value;
        try {
            return Integer.parseInt(cleaned.split("\\.")[0].replaceAll("[^0-9]", ""));
        } catch (RuntimeException e) {
            return 17;
        }
    }

    static String lineOf(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    static String providerFor(String recipeId) {
        if (recipeId.startsWith("openrewrite.")) {
            return OpenRewriteCoreProvider.PROVIDER;
        }
        if (recipeId.startsWith("maven.")) {
            return MavenPomTransformer.PROVIDER;
        }
        if (recipeId.startsWith("jakarta.")) {
            return JakartaNamespaceTransformer.PROVIDER;
        }
        if (recipeId.startsWith("test.")) {
            return TestFrameworkTransformer.PROVIDER;
        }
        if (recipeId.startsWith("config.")) {
            return ConfigurationPropertyTransformer.PROVIDER;
        }
        return OpenRewriteCoreProvider.PROVIDER;
    }

    static String fallbackFor(String recipeId) {
        if (recipeId.startsWith("jakarta.") || recipeId.startsWith("test.")) {
            return "Report residual and let the bounded compile-repair loop handle what remains";
        }
        return "Block the edge; a build descriptor change has no safe fallback";
    }

    static Path generatedRuleFile(StageContext context) {
        return context.migrationRules().resolve("generated-properties/property-migration-rules.json");
    }
}
