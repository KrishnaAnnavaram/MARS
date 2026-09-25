package com.mars.harness.kernel.engine.run;

import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mars.harness.kernel.adapters.store.FilesystemApprovalStore;
import com.mars.harness.kernel.adapters.store.FilesystemArtifactStore;
import com.mars.harness.kernel.adapters.store.FilesystemCheckpointStore;
import com.mars.harness.kernel.adapters.store.FilesystemEvidenceStore;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.decision.DecisionValidator;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.graph.CanonicalGraph;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.proposal.ProposalStore;
import com.mars.harness.kernel.ports.build.BuildModelView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One run's kernel state, loaded from and persisted to the artifact plane.
 *
 * <p>Everything the kernel owns lives here: both identity registries, both ledgers, the evidence
 * log, decisions, checkpoints, proposals, graph, findings and build model. Capabilities never see
 * this class, only the read-only {@code CapabilityContext} built from it.
 */
public final class RunSession {

    public final RunLayout layout;
    public final RunRecord record;
    public final UnifiedPolicy policy;
    public final FilesystemEvidenceStore evidence;
    public final FilesystemArtifactStore artifacts;
    public final FilesystemApprovalStore approvals;
    public final FilesystemCheckpointStore checkpoints;
    public final ProposalStore proposals;
    public final LineageLedger lineage;
    public final ChangeLedger ledger;

    public FileRegistry fileRegistry;
    public IdentityRegistry identity;
    public CanonicalGraph graph;
    public BuildModelView buildModel;
    public List<Finding> findings = new ArrayList<>();

    private RunSession(RunLayout layout, RunRecord record, UnifiedPolicy policy) {
        this.layout = layout;
        this.record = record;
        this.policy = policy;
        this.evidence = new FilesystemEvidenceStore(layout);
        this.artifacts = new FilesystemArtifactStore(layout);
        this.approvals = new FilesystemApprovalStore(layout, new DecisionValidator(policy.reservedActors()),
                policy.policyVersion());
        this.checkpoints = new FilesystemCheckpointStore(layout);
        this.proposals = new ProposalStore(layout);
        this.lineage = new LineageLedger(layout.lineageLedger());
        this.ledger = ChangeLedger.reopen(layout.ledgerFile(), layout.ledgerHead());
    }

    public static RunSession create(RunLayout layout, RunRecord record, UnifiedPolicy policy) {
        try {
            Files.createDirectories(layout.runDir());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        RunSession session = new RunSession(layout, record, policy);
        session.saveRecord();
        return session;
    }

    public static RunSession load(Path runsRoot, String runId, UnifiedPolicy policy) {
        RunLayout layout = new RunLayout(runsRoot, runId);
        if (!Files.isRegularFile(layout.state())) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "No run " + runId + " under " + runsRoot);
        }
        RunRecord record = KernelJson.read(layout.state(), RunRecord.class);
        RunSession session = new RunSession(layout, record, policy);
        if (Files.isRegularFile(layout.fileRegistry())) {
            session.fileRegistry = FileRegistry.load(layout.fileRegistry());
            session.primeRegistryContent();
        }
        if (Files.isRegularFile(layout.identityRegistry())) {
            session.identity = IdentityRegistry.load(layout.identityRegistry());
        }
        if (Files.isRegularFile(layout.canonicalGraph())) {
            session.graph = KernelJson.read(layout.canonicalGraph(), CanonicalGraph.class);
        }
        Path build = layout.area("baseline").resolve("build-model-view.json");
        if (Files.isRegularFile(build)) {
            session.buildModel = KernelJson.read(build, BuildModelView.class);
        }
        if (Files.isRegularFile(layout.findings())) {
            session.findings = new ArrayList<>(KernelJson.mapper().convertValue(KernelJson.read(layout.findings()),
                    new TypeReference<List<Finding>>() { }));
        }
        return session;
    }

    /** Similarity reattachment needs content; FileRegistry does not persist it, so re-prime from the workspace. */
    private void primeRegistryContent() {
        for (FileRecord file : fileRegistry.active()) {
            Path path = layout.workspace().resolve(file.getCurrentPath());
            if (Files.isRegularFile(path)) {
                try {
                    fileRegistry.primeContent(file.getFileId(), Files.readString(path, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException ignored) {
                    // binary or unreadable: similarity simply has no content for this file
                }
            }
        }
    }

    public void saveRecord() {
        Json.writeAtomic(layout.state(), KernelJson.tree(record));
    }

    public void saveIdentity() {
        if (identity != null) {
            identity.save(layout.identityRegistry());
        }
        if (fileRegistry != null) {
            Json.writeAtomic(layout.fileRegistry(), fileRegistry.toNode());
        }
    }

    public void saveGraph() {
        if (graph != null) {
            Json.writeAtomic(layout.canonicalGraph(), KernelJson.tree(graph));
        }
    }

    public void saveFindings() {
        Json.writeAtomic(layout.findings(), KernelJson.tree(findings));
    }

    public void saveBuildModel() {
        if (buildModel != null) {
            artifacts.writeJson("baseline", "build-model-view.json", buildModel);
        }
    }

    public String baselineSeal() {
        return record.machine.baselineSealHash;
    }

    public void note(String text) {
        record.notes.add(text);
        saveRecord();
    }
}
