package com.mars.harness.kernel.engine;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.bootshift.BootshiftBridge;
import com.mars.harness.kernel.adapters.build.PomModelReader;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.migration.CombinedAssessment;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.migration.SequenceRecommendation;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.engine.capability.KernelCapabilityContext;
import com.mars.harness.kernel.engine.discovery.SequenceAdvisor;
import com.mars.harness.kernel.engine.exec.WorkspaceSandbox;
import com.mars.harness.kernel.engine.graph.CanonicalGraphBuilder;
import com.mars.harness.kernel.engine.identity.IdentitySynchronizer;
import com.mars.harness.kernel.engine.mutation.MutationGateway;
import com.mars.harness.kernel.engine.mutation.ProjectApplier;
import com.mars.harness.kernel.engine.report.ReportRenderer;
import com.mars.harness.kernel.engine.run.RunRecord;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.runtime.RuntimePort;
import com.mars.harness.kernel.ports.security.FindingNormalizer;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The unified harness kernel engine: one run, one identity plane, one graph, one evidence plane,
 * one ledger, one Mutation Gateway.
 *
 * <p>{@link #analyze} runs Phases 0 to 4 and stops at Human Gate A. Nothing after that happens
 * without a recorded human decision. {@link #resume} advances the run to the next gate or to its
 * verdict.
 */
public final class HarnessEngine {

    public static final String HARNESS_VERSION = "1.0.0";

    private final EngineConfig config;

    public HarnessEngine(EngineConfig config) {
        this.config = config;
    }

    public EngineConfig config() {
        return config;
    }

    public record AnalyzeRequest(Path repository, List<Path> findingInputs, Map<String, Path> researchInputs,
                                 Path probesFile, boolean skipBuild) {
        public AnalyzeRequest {
            findingInputs = findingInputs == null ? List.of() : List.copyOf(findingInputs);
            researchInputs = researchInputs == null ? Map.of() : Map.copyOf(researchInputs);
        }
    }

    /** What a developer needs to know after each command. */
    public record RunSummary(String runId, String phase, String verdict, String waitingFor, List<String> nextActions,
                             Map<String, Object> highlights) {
    }

    // ================================================================== Phase 0-4

    public RunSummary analyze(AnalyzeRequest request) {
        Path repository = request.repository().toAbsolutePath().normalize();
        if (!Files.isDirectory(repository)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Repository path does not exist: " + repository);
        }
        String runId = HarnessIds.allocate(HarnessIds.Kind.RUN);
        RunLayout layout = new RunLayout(config.runsRoot().toAbsolutePath().normalize(), runId);
        RunRecord record = new RunRecord();
        record.runId = runId;
        record.repository = repository.toString();
        record.createdAt = Instant.now().toString();
        record.policyVersion = config.policy().policyVersion();
        record.harnessVersion = HARNESS_VERSION;
        record.today = config.today().toString();
        record.skipBuild = request.skipBuild();
        RunSession session = RunSession.create(layout, record, config.policy());

        // inputs are copied into the run: the run's evidence must not depend on files that may move
        for (Path input : request.findingInputs()) {
            record.findingInputs.add(copyInput(session, input, "findings/inputs").toString());
        }
        request.researchInputs().forEach((key, path) ->
                record.researchInputs.put(key, copyInput(session, path, "plans/research-inputs").toString()));
        if (request.probesFile() != null) {
            record.probesFile = copyInput(session, request.probesFile(), "baseline").toString();
        }
        writeManifest(session, repository);

        // ---- Phase 0: ingest (Bootshift bootstrap: snapshot, workspace, checkpoint repository, provenance)
        BootshiftBridge bridge = new BootshiftBridge(layout, repository, config.bootshiftHome(), config.bootshiftPolicy(),
                config.networkEnabled());
        BootshiftBridge.AnalysisOutcome bootshift = bridge.analyze();
        session.artifacts.writeJson("inventory", "bootshift-stages.json", bootshift.stages());
        for (BootshiftBridge.StageSummary stage : bootshift.stages()) {
            session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Bootshift " + stage.stageId() + ": "
                            + stage.exitCode(), stage.summary(), "bootshift-output/" + stage.stageId(), List.of(),
                    "Bootshift stage executed unchanged via StageExecutor", EvidenceRecord.Reliability.VERIFIED, null,
                    "inventory/bootshift-stages.json", null, "bootshift");
        }
        if (!bootshift.bootstrapped()) {
            fail(session, "Bootshift bootstrap failed: " + bootshift.stages());
        }
        session.record.machine.transition(RunPhase.SOURCE_SNAPSHOTTED, "immutable snapshot + isolated workspace");
        if (bootshift.sourceProvenance() != null) {
            session.artifacts.writeJson("manifest", "source-provenance.json", bootshift.sourceProvenance());
        }
        session.saveRecord();

        // ---- Phase 1: inventory + persistent identity
        if (!bootshift.inventoried() || bootshift.fileRegistry() == null) {
            fail(session, "Inventory failed: " + bootshift.stages());
        }
        session.fileRegistry = bootshift.fileRegistry();
        session.record.machine.transition(RunPhase.INVENTORY_READY, session.fileRegistry.size() + " files with FILE_ID");
        session.buildModel = new PomModelReader().read(layout.sourceSnapshot(), bootshift.buildModel());
        session.saveBuildModel();
        session.identity = IdentityRegistry.create(runId);
        session.record.repositoryId = session.identity.repositoryId;
        IdentitySynchronizer sync = new IdentitySynchronizer(session, config.codeModel());
        List<String> gaps = sync.baseline(session.buildModel);
        session.saveIdentity();
        session.evidence.record(EvidenceRecord.EvidenceKind.OBSERVATION, "Identity baseline: " + session.identity.coverage(),
                gaps.isEmpty() ? "all parseable files observed" : String.join("; ", gaps), "identity/identity-registry.json",
                List.of(session.identity.repositoryId), "allocated identity (ADR-001, ADR-U002)",
                gaps.isEmpty() ? EvidenceRecord.Reliability.VERIFIED : EvidenceRecord.Reliability.DERIVED, null,
                "identity/identity-registry.json", null, "kernel.identity");
        session.record.machine.transition(RunPhase.IDENTITY_SEALED, "identity registry " + session.identity.contentHash());
        session.saveRecord();

        // ---- Phase 2: canonical application graph
        session.graph = CanonicalGraphBuilder.build(runId, session.identity.repositoryId, bootshift.graph(), session.identity,
                session.fileRegistry, List.of());
        if (!bootshift.graphVerified() && bootshift.graphVerification() != null) {
            session.graph.coverageNotes.add("Bootshift graph verification did not pass its policy floors: "
                    + bootshift.graphVerification().path("failures"));
        }
        session.saveGraph();
        session.record.machine.transition(RunPhase.GRAPH_READY, session.graph.nodeCount() + " nodes, "
                + session.graph.edgeCount() + " edges (" + session.graph.graphSource + ")");
        session.saveRecord();

        // ---- Phase 3: baseline seal
        List<Finding> intake = intake(session, request);
        session.findings = new ArrayList<>(intake);
        session.saveFindings();
        sealBaseline(session);

        // ---- Phase 4: read-only discovery
        discover(session);
        return summary(session);
    }

    private List<Finding> intake(RunSession session, AnalyzeRequest request) {
        List<Finding> findings = new ArrayList<>();
        KernelCapabilityContext context = new KernelCapabilityContext(session, config);
        for (String input : session.record.findingInputs) {
            Path file = Path.of(input);
            if (file.getFileName().toString().endsWith(".advisories.json")) {
                continue; // dependency advisory feeds are matched against the build model during security discovery
            }
            Optional<FindingNormalizer> normalizer = config.normalizers().stream().filter(n -> n.accepts(file)).findFirst();
            if (normalizer.isEmpty()) {
                session.evidence.record(EvidenceRecord.EvidenceKind.UNKNOWN, "No normalizer accepts " + file.getFileName(),
                        null, file.toString(), List.of(), "supported: " + config.normalizers().stream()
                                .map(FindingNormalizer::sourceName).toList(), EvidenceRecord.Reliability.UNKNOWN, null, null,
                        null, "kernel.intake");
                continue;
            }
            findings.addAll(normalizer.get().normalize(file, context.identity(), session.evidence, session.layout.runId()));
        }
        return findings;
    }

    private void sealBaseline(RunSession session) {
        Map<String, Object> manifest = new TreeMap<>();
        JsonNode bootstrap = readBootshift(session, "00-bootstrap", "bootstrap.json");
        manifest.put("source_snapshot_hash", bootstrap == null ? null : bootstrap.path("original_snapshot_hash").asText(null));
        manifest.put("file_registry_seal", session.fileRegistry.seal());
        manifest.put("file_registry_content_hash", session.fileRegistry.contentManifestHash());
        manifest.put("identity_registry_hash", session.identity.contentHash());
        manifest.put("canonical_graph_hash", session.graph.contentHash());
        manifest.put("build_model_hash", KernelJson.hash(session.buildModel));
        manifest.put("findings_snapshot_hash", KernelJson.hash(session.findings));
        manifest.put("policy_version", session.policy.policyVersion());
        manifest.put("environment_hash", KernelJson.hash(KernelJson.read(session.layout.manifest().resolve("environment.json"))));

        // round 0: the reference workflow's pre-migration build and behaviour, before any change
        BuildPort.BuildResult build = null;
        if (!session.record.skipBuild) {
            Path exec = new WorkspaceSandbox(session.layout).prepare("baseline");
            build = config.build().build(exec, BuildPort.Intent.PACKAGE, session.layout.logs().resolve("baseline-build.log"));
            session.artifacts.writeJson("baseline", "baseline-build.json", build);
            session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Baseline build (round 0, package): "
                            + build.outcome().legacyId() + (build.tests() == null ? "" : ", tests " + build.tests()),
                    build.failingTests().isEmpty() ? null : "pre-existing failing tests: " + build.failingTests(), "exec/baseline",
                    List.of(), "recorded, never fixed: pre-existing failures are compared against later, not hidden",
                    build.outcome() == BuildPort.Outcome.TOOL_UNAVAILABLE ? EvidenceRecord.Reliability.UNKNOWN
                            : EvidenceRecord.Reliability.VERIFIED, null, "baseline/baseline-build.json", null, "kernel.baseline");
            manifest.put("baseline_build_outcome", build.outcome().legacyId());
            manifest.put("baseline_tests", build.tests());
            manifest.put("baseline_failing_tests", build.failingTests());
            if (session.record.probesFile != null && (build.outcome() == BuildPort.Outcome.PASSED
                    || build.outcome() == BuildPort.Outcome.TESTS_FAILED)) {
                RuntimePort.ProbeSpec spec = com.mars.harness.kernel.adapters.runtime.ProbeFiles.read(Path.of(session.record.probesFile));
                session.artifacts.writeJson("baseline", "probes.json", spec);
                RuntimePort.RuntimeRun runtime = config.runtime().run(exec, spec, "baseline",
                        session.layout.logs().resolve("runtime-baseline.log"));
                session.artifacts.writeJson("baseline", "runtime-baseline.json", runtime);
                manifest.put("baseline_runtime_started", runtime.started());
                manifest.put("baseline_runtime_hash", KernelJson.hash(runtime.probes()));
                session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Baseline runtime probes: started="
                                + runtime.started() + ", " + runtime.probes().size() + " probe(s)", runtime.failure(),
                        "exec/baseline", List.of(), "probe-runtime semantics (migration reference)",
                        EvidenceRecord.Reliability.VERIFIED, null, "baseline/runtime-baseline.json", null, "kernel.baseline");
            } else {
                manifest.put("baseline_runtime_started", null);
            }
        } else {
            manifest.put("baseline_build_outcome", "NOT_RUN (--skip-build)");
            session.evidence.record(EvidenceRecord.EvidenceKind.UNKNOWN, "Baseline build not run (--skip-build)", null, null,
                    List.of(), "unknown evidence stays unknown", EvidenceRecord.Reliability.UNKNOWN, null, null, null,
                    "kernel.baseline");
        }
        String seal = Hashing.sha256(KernelJson.canonical(manifest));
        manifest.put("baseline_manifest_hash", seal);
        manifest.put("sealed_at", Instant.now().toString());
        session.artifacts.writeJsonOnce("baseline", "baseline-manifest.json", manifest);
        session.record.machine.recordBaselineSeal(seal);
        session.record.machine.transition(RunPhase.BASELINE_SEALED, "baseline " + seal);
        session.saveRecord();
    }

    private void discover(RunSession session) {
        session.record.machine.transition(RunPhase.DISCOVERY_RUNNING, "read-only discovery");
        session.saveRecord();
        String before = workspaceHash(session);
        KernelCapabilityContext context = new KernelCapabilityContext(session, config);

        RemediationCapability.SecurityDiscovery security = config.remediation().discover(context, session.findings);
        session.findings = new ArrayList<>(security.findings());
        session.saveFindings();
        session.artifacts.writeJson("discovery/security", "security-discovery.json", security);

        List<MigrationCapability.Objective> objectives = objectives(session.findings);
        MigrationAssessment assessment = config.migration().assess(new KernelCapabilityContext(session, config), objectives);
        session.artifacts.writeJson("discovery/migration", "migration-assessment.json", assessment);
        session.record.migrationAssessmentId = assessment.assessmentId();

        CanonicalGraphBuilder.attachFindings(session.graph, session.findings);
        session.saveGraph();

        String after = workspaceHash(session);
        if (!before.equals(after)) {
            fail(session, "Discovery changed tracked source (workspace hash " + before + " -> " + after
                    + "); discovery must be read-only");
        }
        session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Discovery was read-only: workspace hash unchanged",
                after, session.layout.workspace().toString(), List.of(), "workspace content hash before/after discovery",
                EvidenceRecord.Reliability.VERIFIED, null, null, null, "kernel.discovery");

        SequenceRecommendation sequence = SequenceAdvisor.advise(assessment, session.findings);
        CombinedAssessment combined = SequenceAdvisor.combine(session.layout.runId(), assessment, session.findings, sequence);
        session.artifacts.writeJson("discovery", "combined-assessment.json", combined);
        session.record.combinedAssessmentHash = KernelJson.hash(combined);
        session.record.machine.transition(RunPhase.DISCOVERY_READY, "assessment " + assessment.trafficLight());
        session.record.machine.transition(RunPhase.WAITING_FOR_EXECUTION_DECISION, "Human Gate A");
        session.saveRecord();
        new ReportRenderer(session, config).renderAnalysis();
    }

    static List<MigrationCapability.Objective> objectives(List<Finding> findings) {
        List<MigrationCapability.Objective> objectives = new ArrayList<>();
        for (Finding f : findings) {
            if (f.status() == Finding.FindingStatus.FIXED || f.status() == Finding.FindingStatus.CLOSED) {
                continue;
            }
            objectives.add(new MigrationCapability.Objective("OBJ-" + f.findingId(), "REMEDIATE_FINDING", f.findingId(),
                    "Remediate " + f.sourceFindingId() + " " + String.join(",", f.cwe()) + " (" + f.title() + ")",
                    f.severity(), f.platformRequirement()));
        }
        return objectives;
    }

    // ================================================================== decisions (Human Gates)

    public Decision decideExecution(String runId, String strategy, String actor, String role, String rationale) {
        RunSession session = load(runId);
        requirePhase(session, RunPhase.WAITING_FOR_EXECUTION_DECISION);
        CombinedAssessment combined = KernelJson.read(session.layout.area("discovery").resolve("combined-assessment.json"),
                CombinedAssessment.class);
        Decision decision = session.approvals.record(new Decision(null, runId, Decision.DecisionType.EXECUTION_STRATEGY,
                strategy, combined.recommendedStrategy(), null, null, List.of(), List.of(), List.of(), List.of(), null,
                null, session.record.combinedAssessmentHash, session.baselineSeal(), null, actor, role, null, rationale,
                null, null, null));
        Decision.ExecutionStrategy chosen = Decision.ExecutionStrategy.valueOf(decision.selected());
        session.record.strategy = chosen.name();
        session.record.executionDecisionId = decision.decisionId();
        session.record.migrationDeclined = !chosen.includesMigration();
        session.record.stopRequested = chosen == Decision.ExecutionStrategy.STOP;
        session.record.machine.transition(RunPhase.EXECUTION_PLANNED, "strategy " + chosen + " by " + decision.actor());
        session.saveRecord();
        session.evidence.record(EvidenceRecord.EvidenceKind.DECISION, "Execution strategy " + chosen + " chosen (harness "
                        + "recommended " + combined.recommendedStrategy() + ")", decision.rationale(),
                "decisions/" + decision.decisionId() + ".json", List.of(decision.decisionId()),
                "Human Gate A; bound to assessment " + session.record.combinedAssessmentHash, EvidenceRecord.Reliability.ASSERTED,
                null, "decisions/" + decision.decisionId() + ".json", null, "human");
        return decision;
    }

    public Decision decideProposal(String runId, String proposalId, String verdict, String actor, String role,
                                   String rationale) {
        RunSession session = load(runId);
        ChangeProposal proposal = session.proposals.find(proposalId).orElseThrow(() ->
                new HarnessOutcomeException(OutcomeCategory.REFUSAL, "No proposal " + proposalId + " in " + runId));
        Decision decision = session.approvals.record(new Decision(null, runId, Decision.DecisionType.PROPOSAL_APPROVAL,
                verdict, null, proposalId, proposal.proposalHash(), proposal.findingRefs(), proposal.affectedFileIds(),
                proposal.affectedSymbolIds(), proposal.affectedStatementIds(), null, null, null, session.baselineSeal(), null,
                actor, role, null, rationale, null, null, null));
        session.evidence.record(EvidenceRecord.EvidenceKind.DECISION, "Proposal " + proposalId + " " + decision.selected()
                        + " by " + decision.actor(), decision.rationale(), "decisions/" + decision.decisionId() + ".json",
                List.of(decision.decisionId(), proposalId), "Human Gate B; bound to proposal hash " + proposal.proposalHash()
                        + " and baseline " + session.baselineSeal(), EvidenceRecord.Reliability.ASSERTED, null,
                "decisions/" + decision.decisionId() + ".json", null, "human");
        new ReportRenderer(session, config).renderLegacyPlans();
        return decision;
    }

    public Decision decideMigrationPlan(String runId, String verdict, String actor, String role, String rationale) {
        RunSession session = load(runId);
        requirePhase(session, RunPhase.WAITING_FOR_MIGRATION_APPROVAL);
        return session.approvals.record(new Decision(null, runId, Decision.DecisionType.MIGRATION_PLAN_APPROVAL, verdict,
                null, null, null, List.of(), List.of(), List.of(), List.of(), session.record.migrationPlanId,
                session.record.migrationPlanHash, null, session.baselineSeal(), null, actor, role, null, rationale, null,
                null, null));
    }

    public Decision decidePostSecurityMigration(String runId, String choice, String actor, String role, String rationale) {
        RunSession session = load(runId);
        requirePhase(session, RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION);
        Path reassessment = session.layout.area("discovery").resolve("migration").resolve("post-security-reassessment.json");
        JsonNode node = KernelJson.read(reassessment);
        Decision decision = session.approvals.record(new Decision(null, runId, Decision.DecisionType.POST_SECURITY_MIGRATION,
                choice, node.path("current_traffic_light").asText(null), null, null, List.of(), List.of(), List.of(),
                List.of(), null, null, node.path("current_assessment_hash").asText(null), session.baselineSeal(), null, actor,
                role, null, rationale, null, null, null));
        session.record.postSecurityDecisionId = decision.decisionId();
        session.saveRecord();
        return decision;
    }

    public Decision decideApply(String runId, String verdict, String actor, String role, String rationale) {
        RunSession session = load(runId);
        return session.approvals.record(new Decision(null, runId, Decision.DecisionType.APPLY_TO_PROJECT, verdict, null, null,
                null, List.of(), List.of(), List.of(), List.of(), null, null, null, session.baselineSeal(), session.ledger.head(),
                actor, role, null, rationale, null, null, null));
    }

    public ProjectApplier.ApplyResult applyToProject(String runId, String decisionId) {
        RunSession session = load(runId);
        Decision decision = session.approvals.find(decisionId).orElseThrow(() ->
                new HarnessOutcomeException(OutcomeCategory.AUTHORIZATION_MISSING, "No decision " + decisionId));
        boolean green = true;
        Path execution = session.layout.area("plans").resolve("migration-execution.json");
        if (Files.isRegularFile(execution)) {
            green = KernelJson.read(execution, MigrationCapability.MigrationExecution.class).lastRoundGreen();
        }
        return ProjectApplier.apply(session, Path.of(session.record.repository), decision, green);
    }

    // ================================================================== manual inputs (still proposals, never authority)

    /**
     * Registers a human- or agent-produced patch as a proposal. It never applies anything: the
     * proposal must be approved like any other. {@code providerType} MANUAL_PATCH or LLM. An LLM
     * patch needs model, prompt and response hashes.
     */
    public ChangeProposal submitPatch(String runId, List<String> findingIds, Map<String, String> newContents, String reason,
                                      ChangeProposal.ProviderType providerType, ChangeProposal.Provenance provenance) {
        return submitPatch(runId, findingIds, newContents, Map.of(), reason, providerType, provenance);
    }

    /**
     * As {@link #submitPatch(String, List, Map, String, ChangeProposal.ProviderType, ChangeProposal.Provenance)},
     * with renames ({@code old path -> new path}). A renamed path that also has new content is one
     * RENAME edit carrying that content, so the FILE_ID and the sub-file identities travel with it.
     */
    public ChangeProposal submitPatch(String runId, List<String> findingIds, Map<String, String> newContents,
                                      Map<String, String> renames, String reason, ChangeProposal.ProviderType providerType,
                                      ChangeProposal.Provenance provenance) {
        RunSession session = load(runId);
        List<ChangeProposal.FileEdit> edits = new ArrayList<>();
        List<String> fileIds = new ArrayList<>();
        Map<String, String> base = new LinkedHashMap<>();
        Map<String, String> contents = new LinkedHashMap<>();
        newContents.forEach((k, v) -> contents.put(k.replace('\\', '/'), v));
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            String path = rename.getKey().replace('\\', '/');
            String target = rename.getValue().replace('\\', '/');
            var record = session.fileRegistry.byPath(path).orElseThrow(() -> new HarnessOutcomeException(
                    OutcomeCategory.REFUSAL, "Cannot rename an unregistered path: " + path));
            fileIds.add(record.getFileId());
            base.put(record.getFileId(), record.getCurrentSha256());
            String before = read(session.layout.workspace().resolve(path));
            String after = contents.containsKey(path) ? contents.remove(path) : null;
            edits.add(new ChangeProposal.FileEdit(record.getFileId(), path, target, "RENAME", after,
                    com.bootshift.adapters.mutation.FileMutationGateway.unifiedDiff(path, target, before,
                            after == null ? before : after, 3), null, List.of()));
        }
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            String path = entry.getKey();
            var record = session.fileRegistry.byPath(path);
            if (record.isPresent()) {
                fileIds.add(record.get().getFileId());
                base.put(record.get().getFileId(), record.get().getCurrentSha256());
                String before = read(session.layout.workspace().resolve(path));
                edits.add(new ChangeProposal.FileEdit(record.get().getFileId(), path, null, "MODIFY", entry.getValue(),
                        com.bootshift.adapters.mutation.FileMutationGateway.unifiedDiff(path, path, before, entry.getValue(), 3),
                        null, List.of()));
            } else {
                edits.add(new ChangeProposal.FileEdit(null, path, null, "CREATE", entry.getValue(),
                        com.bootshift.adapters.mutation.FileMutationGateway.unifiedDiff(null, path, null, entry.getValue(), 3),
                        null, List.of()));
            }
        }
        List<String> symbols = new ArrayList<>();
        for (String findingId : findingIds) {
            session.findings.stream().filter(f -> f.findingId().equals(findingId) && f.symbolId() != null)
                    .forEach(f -> symbols.add(f.symbolId()));
        }
        ChangeProposal proposal = new ChangeProposal(HarnessIds.allocate(HarnessIds.Kind.PROPOSAL), runId,
                findingIds.isEmpty() ? ChangeProposal.Capability.MANUAL : ChangeProposal.Capability.SECURITY,
                providerType == ChangeProposal.ProviderType.LLM ? "llm-repair-agent" : "manual-approved-patch", providerType,
                "1", reason, findingIds, List.of(), List.of(), List.of(), fileIds, List.of(), List.of(), edits,
                "Resolve " + findingIds, ChangeProposal.Risk.MEDIUM, base, session.baselineSeal(), Instant.now().toString(),
                provenance, false, null, null);
        MutationGateway gateway = new MutationGateway(session, new IdentitySynchronizer(session, config.codeModel()), (b, f) -> { });
        ChangeProposal registered = gateway.register(proposal);
        new ReportRenderer(session, config).renderLegacyPlans();
        return registered;
    }

    public void submitResearch(String runId, String findingOrIssueId, Path analysisJson) {
        RunSession session = load(runId);
        Path stored = copyInput(session, analysisJson, "plans/research-inputs");
        session.record.researchInputs.put(findingOrIssueId, stored.toString());
        session.saveRecord();
    }

    // ================================================================== advance, status, report

    public RunSummary resume(String runId, boolean acceptPending) {
        RunSession session = load(runId);
        session.record.acceptPending = acceptPending;
        session.saveRecord();
        new RunAdvancer(session, config).advance();
        return summary(load(runId));
    }

    public RunSummary status(String runId) {
        return summary(load(runId));
    }

    public RunSession load(String runId) {
        return RunSession.load(config.runsRoot().toAbsolutePath().normalize(), runId, config.policy());
    }

    public String report(String runId) {
        RunSession session = load(runId);
        return new ReportRenderer(session, config).renderFinal();
    }

    RunSummary summary(RunSession session) {
        RunPhase phase = session.record.machine.current;
        List<String> next = new ArrayList<>();
        String waiting = null;
        String run = session.layout.runId();
        switch (phase) {
            case WAITING_FOR_EXECUTION_DECISION -> {
                waiting = "Human Gate A: choose an execution strategy";
                next.add("harness decide execution --run " + run + " --strategy <MIGRATE_FIRST|SECURITY_FIRST|MIGRATION_ONLY|"
                        + "SECURITY_ONLY|ANALYZE_ONLY|STOP> --actor <name> --role <role> --rationale <text>");
            }
            case WAITING_FOR_REMEDIATION_APPROVAL -> {
                waiting = "Human Gate B: approve, reject or defer remediation proposals";
                next.add("harness proposals --run " + run);
                next.add("harness approve remediation --run " + run + " --proposal <PROP-...> --verdict APPROVED --actor <name> "
                        + "--role <role> --rationale <text>");
                next.add("harness resume --run " + run);
            }
            case WAITING_FOR_MIGRATION_APPROVAL -> {
                waiting = "Policy requires the migration plan to be approved";
                next.add("harness approve migration --run " + run + " --verdict APPROVED --actor <name> --role <role> --rationale <text>");
            }
            case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> {
                waiting = "Human Gate A2: migration re-assessed after security work";
                next.add("harness decide migration --run " + run + " --decision <PROCEED|SKIP|STOP> --actor <name> --role <role> "
                        + "--rationale <text>");
            }
            case NEEDS_HUMAN -> {
                Path verdictFile = session.layout.area("reports").resolve("verdict.json");
                if (session.record.verdict != null && Files.isRegularFile(verdictFile)) {
                    // derived from the verdict, not from a note that may predate later decisions
                    JsonNode pending = KernelJson.read(verdictFile).path("pending_decisions");
                    waiting = "Verdict NEEDS_HUMAN: " + pending.size() + " decision(s) outstanding (a missing decision is "
                            + "not approval): " + (pending.isEmpty() ? "" : pending.get(0).asText())
                            + (pending.size() > 1 ? " …" : "");
                } else {
                    waiting = "A human action is required: " + lastNote(session);
                }
                next.add("harness report --run " + run);
            }
            case EXECUTION_PLANNED -> next.add("harness resume --run " + run);
            default -> {
                if (!phase.terminal()) {
                    next.add("harness resume --run " + run);
                }
            }
        }
        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("strategy", session.record.strategy);
        highlights.put("baseline_seal", session.baselineSeal());
        Path combined = session.layout.area("discovery").resolve("combined-assessment.json");
        if (Files.isRegularFile(combined)) {
            JsonNode node = KernelJson.read(combined);
            highlights.put("migration_traffic_light", node.path("traffic_light").asText());
            highlights.put("migration_complexity", node.path("complexity").asText());
            highlights.put("migration_effort_score", node.path("effort_score").asInt());
            highlights.put("evidence_confidence", node.path("evidence_confidence").asText());
            highlights.put("recommended_strategy", node.path("recommended_strategy").asText());
            highlights.put("sequence", node.path("sequence").path("sequence").asText());
            highlights.put("open_findings", node.path("security").path("total").asInt());
        }
        highlights.put("proposals", session.record.proposalStatus);
        highlights.put("report", session.layout.area("reports").toString());
        return new RunSummary(run, phase.name(), session.record.verdict, waiting, next, highlights);
    }

    private static String lastNote(RunSession session) {
        return session.record.notes.isEmpty() ? "" : session.record.notes.get(session.record.notes.size() - 1);
    }

    // ================================================================== helpers

    private static void requirePhase(RunSession session, RunPhase phase) {
        if (session.record.machine.current != phase) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Run " + session.layout.runId() + " is at "
                    + session.record.machine.current + ", not " + phase);
        }
    }

    private static void fail(RunSession session, String reason) {
        session.record.notes.add("FAILED: " + reason);
        session.record.machine.transition(RunPhase.FAILED, reason);
        session.saveRecord();
        throw new HarnessOutcomeException(OutcomeCategory.FAILURE, reason);
    }

    private void writeManifest(RunSession session, Path repository) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java_version", System.getProperty("java.version"));
        environment.put("java_vendor", System.getProperty("java.vendor"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        BuildPort.Availability availability = config.build().availability(repository);
        environment.put("build_tool", availability.tool());
        environment.put("build_tool_available", availability.available());
        environment.put("build_tool_executable", availability.version());
        environment.put("build_tool_unavailable_reason", availability.reason());
        environment.put("network_enabled", config.networkEnabled());
        environment.put("harness_version", HARNESS_VERSION);
        environment.put("policy_version", config.policy().policyVersion());
        Path sources = config.harnessRoot().resolve("legacy-sources").resolve("SOURCES.json");
        if (Files.isRegularFile(sources)) {
            environment.put("legacy_sources", KernelJson.read(sources).path("sources"));
        }
        session.artifacts.writeJson("manifest", "environment.json", environment);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", session.layout.runId());
        run.put("repository", repository.toString());
        run.put("created_at", session.record.createdAt);
        run.put("evaluation_date", session.record.today);
        run.put("finding_inputs", session.record.findingInputs);
        run.put("research_inputs", session.record.researchInputs);
        run.put("probes_file", session.record.probesFile);
        run.put("skip_build", session.record.skipBuild);
        session.artifacts.writeJson("manifest", "run.json", run);
    }

    private static Path copyInput(RunSession session, Path input, String area) {
        return session.artifacts.importFile(area, input);
    }

    private static JsonNode readBootshift(RunSession session, String stage, String artifact) {
        return new com.bootshift.core.domain.OutputLayout(session.layout.bootshiftOutput()).readLatest(stage, artifact);
    }

    /** Content hash of every tracked workspace file: the read-only-discovery witness. */
    static String workspaceHash(RunSession session) {
        Path root = session.layout.workspace();
        List<String> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    entries.add(root.relativize(p).toString().replace('\\', '/') + ":" + Hashing.sha256File(p));
                } catch (IOException e) {
                    entries.add(p + ":unreadable");
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Hashing.manifestHash(entries);
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
