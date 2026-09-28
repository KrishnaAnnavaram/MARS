package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.store.FilesystemApprovalStore;
import com.mars.harness.kernel.adapters.store.FilesystemCheckpointStore;
import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.decision.DecisionValidator;
import com.mars.harness.kernel.core.evidence.EvidenceLog;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.migration.CombinedAssessment;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.proposal.ProposalStore;
import com.mars.harness.kernel.engine.run.RunRecord;
import com.mars.harness.kernel.ports.checkpoint.CheckpointStore;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-only access to one run's artifact plane, for one request.
 *
 * <p>Everything is read from the files the engine published; nothing is computed that the engine
 * did not already decide. Each artifact is read at most once per reader. The reader never writes:
 * it uses only the read side of the kernel stores, and it never loads a {@code RunSession} (whose
 * saves would publish state).
 */
public final class RunReader {

    private final RunLayout layout;
    private final UnifiedPolicy policy;
    private final Lazy<RunRecord> record;
    private final Lazy<List<Finding>> findings;
    private final Lazy<List<ChangeProposal>> proposals;
    private final Lazy<List<Decision>> decisions;
    private final Lazy<List<String>> tamperedDecisions;
    private final Lazy<List<EvidenceRecord>> evidence;
    private final Lazy<List<RemediationCapability.RemediationPlan>> remediationPlans;
    private final Lazy<Optional<RemediationCapability.SecurityDiscovery>> discovery;

    public RunReader(Path runsRoot, String runId, UnifiedPolicy policy) {
        this.layout = new RunLayout(runsRoot, runId);
        this.policy = policy;
        this.record = new Lazy<>(() -> KernelJson.read(layout.state(), RunRecord.class));
        this.findings = new Lazy<>(() -> Files.isRegularFile(layout.findings())
                ? KernelJson.mapper().convertValue(KernelJson.read(layout.findings()), new TypeReference<List<Finding>>() { })
                : List.of());
        this.proposals = new Lazy<>(() -> new ProposalStore(layout).all());
        this.decisions = new Lazy<>(() -> approvals().all());
        this.tamperedDecisions = new Lazy<>(() -> Files.isDirectory(layout.decisions()) ? approvals().verifyIntegrity()
                : List.of());
        this.evidence = new Lazy<>(() -> EvidenceLog.open(layout.evidenceLog(), runId).all());
        this.remediationPlans = new Lazy<>(() -> json("plans/remediation-plans.json").map(n -> KernelJson.mapper()
                .convertValue(n, new TypeReference<List<RemediationCapability.RemediationPlan>>() { })).orElse(List.of()));
        this.discovery = new Lazy<>(() -> json("discovery/security/security-discovery.json")
                .map(n -> KernelJson.convert(n, RemediationCapability.SecurityDiscovery.class)));
    }

    public static boolean exists(Path runsRoot, String runId) {
        return runId != null && runId.matches("RUN-[0-9A-Z]{26}") && Files.isRegularFile(new RunLayout(runsRoot, runId).state());
    }

    public RunLayout layout() {
        return layout;
    }

    public String runId() {
        return layout.runId();
    }

    public RunRecord record() {
        return record.get();
    }

    public List<Finding> findings() {
        return findings.get();
    }

    public Optional<Finding> finding(String findingId) {
        return findings().stream().filter(f -> f.findingId().equals(findingId)).findFirst();
    }

    public List<ChangeProposal> proposals() {
        return proposals.get();
    }

    public Optional<ChangeProposal> proposal(String proposalId) {
        return proposals().stream().filter(p -> p.proposalId().equals(proposalId)).findFirst();
    }

    public String proposalStatus(String proposalId) {
        return record().proposalStatus.get(proposalId);
    }

    public List<Decision> decisions() {
        return decisions.get();
    }

    /** Latest decision for a proposal (later decisions supersede earlier ones), as the engine reads it. */
    public Optional<Decision> latestDecisionFor(String proposalId) {
        List<Decision> matching = decisions().stream().filter(d -> d.type() == Decision.DecisionType.PROPOSAL_APPROVAL
                && proposalId.equals(d.proposalId())).toList();
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    public Optional<Decision> latestDecision(Decision.DecisionType type) {
        List<Decision> matching = decisions().stream().filter(d -> d.type() == type).toList();
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    /** IDs of decisions whose integrity hash no longer matches (modified after recording). */
    public List<String> tamperedDecisions() {
        return tamperedDecisions.get();
    }

    public List<EvidenceRecord> evidence() {
        return evidence.get();
    }

    /** Recomputes the evidence hash chain; empty means intact. */
    public List<String> evidenceChainViolations() {
        return EvidenceLog.verify(layout.evidenceLog());
    }

    public List<LineageLedger.Entry> lineage() {
        return new LineageLedger(layout.lineageLedger()).entries();
    }

    public List<CheckpointStore.Checkpoint> checkpoints() {
        return new FilesystemCheckpointStore(layout).all();
    }

    public FilesystemExecutionEventStore events() {
        return new FilesystemExecutionEventStore(layout.events());
    }

    public Optional<CombinedAssessment> combinedAssessment() {
        return json("discovery/combined-assessment.json").map(n -> KernelJson.convert(n, CombinedAssessment.class));
    }

    public Optional<MigrationAssessment> migrationAssessment() {
        return json("discovery/migration/migration-assessment.json").map(n -> KernelJson.convert(n, MigrationAssessment.class));
    }

    public Optional<MigrationAssessment> refreshedMigrationAssessment() {
        return json("discovery/migration/migration-assessment-refreshed.json")
                .map(n -> KernelJson.convert(n, MigrationAssessment.class));
    }

    public Optional<JsonNode> postSecurityReassessment() {
        return json("discovery/migration/post-security-reassessment.json");
    }

    public Optional<RemediationCapability.SecurityDiscovery> securityDiscovery() {
        return discovery.get();
    }

    public List<RemediationCapability.RemediationPlan> remediationPlans() {
        return remediationPlans.get();
    }

    public Optional<RemediationCapability.VerificationReport> verification(String planId) {
        return json("validation/security/" + planId + ".json")
                .map(n -> KernelJson.convert(n, RemediationCapability.VerificationReport.class));
    }

    public Optional<MigrationCapability.MigrationPlan> migrationPlan() {
        return json("plans/migration-plan.json").map(n -> KernelJson.convert(n, MigrationCapability.MigrationPlan.class));
    }

    public Optional<MigrationCapability.MigrationExecution> migrationExecution() {
        return json("plans/migration-execution.json")
                .map(n -> KernelJson.convert(n, MigrationCapability.MigrationExecution.class));
    }

    public Optional<MigrationCapability.CapabilityValidation> migrationValidation() {
        return json("validation/migration-validation.json")
                .map(n -> KernelJson.convert(n, MigrationCapability.CapabilityValidation.class));
    }

    public Optional<ValidationResult> validation() {
        return json("validation/latest.json").map(n -> KernelJson.convert(n, ValidationResult.class));
    }

    public Optional<Verdict> verdict() {
        return json("reports/verdict.json").map(n -> KernelJson.convert(n, Verdict.class));
    }

    public Optional<JsonNode> baselineManifest() {
        return json("baseline/baseline-manifest.json");
    }

    public Optional<JsonNode> baselineBuild() {
        return json("baseline/baseline-build.json");
    }

    public Optional<JsonNode> runManifest() {
        return json("manifest/run.json");
    }

    public Optional<JsonNode> environment() {
        return json("manifest/environment.json");
    }

    public Optional<JsonNode> graph() {
        return json("graph/canonical-graph.json");
    }

    /** A JSON artifact by run-relative path, if it exists and stays inside the run directory. */
    public Optional<JsonNode> json(String relative) {
        return file(relative).map(KernelJson::read);
    }

    /** A run-relative file, refused if it would escape the run directory. */
    public Optional<Path> file(String relative) {
        Path root = layout.runDir().toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private FilesystemApprovalStore approvals() {
        return new FilesystemApprovalStore(layout, new DecisionValidator(policy.reservedActors()), policy.policyVersion());
    }

    /** Memoized supplier; one read per request. */
    private static final class Lazy<T> {
        private final Supplier<T> supplier;
        private T value;
        private boolean loaded;

        Lazy(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            if (!loaded) {
                value = supplier.get();
                loaded = true;
            }
            return value;
        }
    }
}
