package com.mars.harness.kernel.engine.mutation;

import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileStatus;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.ports.scm.ScmPort;
import com.bootshift.ports.transformation.TransformationPort;
import com.mars.harness.kernel.adapters.bootshift.BootshiftBridge;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.engine.identity.IdentitySynchronizer;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.proposal.ProposalStore;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.checkpoint.CheckpointStore;
import com.mars.harness.kernel.ports.mutation.MutationPort;
import com.mars.harness.kernel.ports.mutation.ProposalSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The single Mutation Gateway (spec §18). It is the only path by which tracked customer source
 * changes.
 *
 * <p>For every proposal, in order:
 *
 * <ol>
 *   <li>baseline sealed</li>
 *   <li>identity resolved: every edit names a registered, active FILE_ID at its current path</li>
 *   <li>authorization: an exact-hash, baseline-scoped human approval, or, for deterministic
 *       migration rules only, the human execution decision plus the frozen plan's rule
 *       allowlist</li>
 *   <li>human approval confirmed not superseded and integrity-intact</li>
 *   <li>scope: files within the proposal's FILE_IDs; changed lines inside only the proposal's
 *       SYMBOL_IDs</li>
 *   <li>base hash: a stale proposal is refused</li>
 *   <li>path inside the workspace (Bootshift's containment and symlink checks)</li>
 *   <li>mutation budget</li>
 * </ol>
 *
 * <p>Then it delegates the write to Bootshift's {@link FileMutationGateway}: operation
 * detection, FILE_ID registry update, patch artifact, hash-chained ChangeEvent and Git
 * checkpoint. After that it reattaches symbol and statement identity, appends lineage, records a
 * checkpoint, publishes evidence and runs bypass detection.
 *
 * <p>Every attempt is recorded, including refusals. Nothing is silently skipped.
 */
public final class MutationGateway implements MutationPort {

    private static final Set<ChangeProposal.ProviderType> EXECUTION_AUTHORIZABLE = EnumSet.of(
            ChangeProposal.ProviderType.DETERMINISTIC_RULE, ChangeProposal.ProviderType.OPENREWRITE,
            ChangeProposal.ProviderType.DEPENDENCY_UPGRADER, ChangeProposal.ProviderType.COMPILER_REPAIR);

    private final RunSession session;
    private final IdentitySynchronizer identitySync;
    private final BiConsumer<String, List<String>> postBatch;
    private final FileMutationGateway delegate;
    private final ScmPort scm = BootshiftBridge.scm();

    /**
     * @param postBatch graph rebuild and diff after identity reattachment (spec §20). Receives the
     *                  batch label and the changed FILE_IDs.
     */
    public MutationGateway(RunSession session, IdentitySynchronizer identitySync,
                           BiConsumer<String, List<String>> postBatch) {
        this.session = session;
        this.identitySync = identitySync;
        this.postBatch = postBatch;
        this.delegate = BootshiftBridge.gateway(session.layout, session.fileRegistry, session.ledger,
                new FileMutationGateway.BaselineSealVerifier() {
                    @Override
                    public boolean sealed() {
                        return session.record.machine.baselineSealed;
                    }

                    @Override
                    public String sealHash() {
                        return session.record.machine.baselineSealHash;
                    }
                });
    }

    /** Registers a proposal: immutable file plus a PROPOSED ledger event (the attempt is on record). */
    public ChangeProposal register(ChangeProposal proposal) {
        ChangeProposal stored = session.proposals.save(proposal);
        if (!session.record.proposalStatus.containsKey(proposal.proposalId())) {
            session.ledger.append(new ChangeEvent()
                    .setRunId(session.layout.runId())
                    .setEdgeId(proposal.proposalId())
                    .setAgent(proposal.capability() + "/" + proposal.provider())
                    .setProvider(new ChangeEvent.Provider(proposal.providerType().name(), proposal.provider(),
                            proposal.providerVersion()))
                    .setRecipeId(proposal.provenance() == null ? null : proposal.provenance().ruleId())
                    .setKnowledgeRefs(proposal.knowledgeRefs())
                    .setImpactRefs(concat(proposal.findingRefs(), proposal.migrationRefs()))
                    .setFileId(proposal.affectedFileIds().isEmpty() ? null : proposal.affectedFileIds().get(0))
                    .setOperation(ChangeEvent.Operation.MODIFY)
                    .setStatus(ChangeEvent.Status.PROPOSED)
                    .setRejectionReason("proposal " + proposal.proposalId() + " sha256 " + proposal.proposalHash()));
            session.record.proposalStatus.put(proposal.proposalId(), ProposalStore.Status.PROPOSED.name());
            session.saveRecord();
        }
        return stored;
    }

    @Override
    public List<Outcome> apply(Authorization authorization, List<ChangeProposal> proposals) {
        if (!session.record.machine.baselineSealed) {
            throw new HarnessOutcomeException(OutcomeCategory.BASELINE_INVALID,
                    "The Mutation Gateway refuses to write before the baseline is sealed");
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (ChangeProposal proposal : proposals) {
            outcomes.add(applyOne(authorization, register(proposal)));
        }
        return outcomes;
    }

    private Outcome applyOne(Authorization authorization, ChangeProposal proposal) {
        String id = proposal.proposalId();
        // duplicate protection: a resumed run never re-applies an applied proposal
        if (ProposalStore.Status.APPLIED.name().equals(session.record.proposalStatus.get(id))
                || ProposalStore.Status.VALIDATED.name().equals(session.record.proposalStatus.get(id))) {
            return new Outcome(id, ProposalSink.Status.APPLIED, "already applied (idempotent resume; not re-applied)",
                    session.record.proposalChanges.getOrDefault(id, List.of()), proposal.affectedFileIds(), null);
        }
        String denial = identityAndScope(proposal);
        if (denial != null) {
            return refuse(proposal, denial.startsWith("STALE") ? ProposalSink.Status.STALE : ProposalSink.Status.REJECTED,
                    denial, null);
        }
        AuthorizationResult auth = authorize(authorization, proposal);
        if (auth.denial() != null) {
            ProposalSink.Status status = auth.awaiting() ? ProposalSink.Status.AWAITING_APPROVAL
                    : auth.denial().startsWith("STALE") ? ProposalSink.Status.STALE : ProposalSink.Status.REJECTED;
            if (auth.awaiting()) {
                session.record.proposalStatus.put(id, ProposalStore.Status.AWAITING_APPROVAL.name());
                session.saveRecord();
                return new Outcome(id, status, auth.denial(), List.of(), List.of(), null);
            }
            return refuse(proposal, status, auth.denial(), auth.decisionId());
        }
        return write(proposal, auth.decisionId());
    }

    // ------------------------------------------------------------------ checks

    private String identityAndScope(ChangeProposal proposal) {
        if (proposal.strategyOnly()) {
            return "STRATEGY_ONLY: a strategy-only proposal carries no edits; a concrete fix proposal must be produced "
                    + "and approved on its own";
        }
        if (proposal.edits().isEmpty()) {
            return "EMPTY: proposal has no edits";
        }
        if (proposal.baselineSeal() != null && !proposal.baselineSeal().equals(session.baselineSeal())) {
            return "STALE_PROPOSAL: computed against baseline " + proposal.baselineSeal() + " but the run is sealed as "
                    + session.baselineSeal();
        }
        if (proposal.llmAuthored() && (proposal.provenance() == null || proposal.provenance().model() == null
                || proposal.provenance().promptHash() == null || proposal.provenance().responseHash() == null)) {
            return "PROVENANCE_MISSING: an LLM-authored proposal must record model, prompt hash and response hash";
        }
        int maxFiles = session.policy.mutation().maxFilesPerBatch();
        if (proposal.edits().size() > maxFiles) {
            return "BUDGET_EXCEEDED: " + proposal.edits().size() + " file edits exceed the per-proposal budget " + maxFiles;
        }
        for (ChangeProposal.FileEdit edit : proposal.edits()) {
            String path = edit.path() == null ? null : edit.path().replace('\\', '/');
            if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.matches("^[A-Za-z]:.*")) {
                return "PATH_REFUSED: edit path must be workspace-relative without traversal: " + edit.path();
            }
            String op = edit.operation() == null ? "MODIFY" : edit.operation();
            if ("RENAME".equals(op)) {
                String target = edit.newPath() == null ? null : edit.newPath().replace('\\', '/');
                if (target == null || target.isBlank() || target.startsWith("/") || target.contains("..")
                        || target.matches("^[A-Za-z]:.*")) {
                    return "PATH_REFUSED: rename target must be workspace-relative without traversal: " + edit.newPath();
                }
                Optional<FileRecord> occupied = session.fileRegistry.byPath(target);
                if (occupied.isPresent() && occupied.get().getStatus() == FileStatus.ACTIVE) {
                    return "SCOPE_VIOLATION: RENAME onto a registered file " + target;
                }
            }
            if ("CREATE".equals(op)) {
                Optional<FileRecord> existing = session.fileRegistry.byPath(path);
                if (existing.isPresent() && existing.get().getStatus() == FileStatus.ACTIVE) {
                    return "SCOPE_VIOLATION: CREATE over a registered file " + path;
                }
                continue;
            }
            if (edit.fileId() == null) {
                return "IDENTITY_UNRESOLVED: " + op + " of " + path + " names no FILE_ID";
            }
            Optional<FileRecord> record = session.fileRegistry.byId(edit.fileId());
            if (record.isEmpty() || record.get().getStatus() != FileStatus.ACTIVE) {
                return "IDENTITY_UNRESOLVED: " + edit.fileId() + " is not an active registered file";
            }
            if (!record.get().getCurrentPath().equals(path)) {
                return "STALE_PROPOSAL: " + edit.fileId() + " is now at " + record.get().getCurrentPath()
                        + ", not " + path;
            }
            if (!proposal.affectedFileIds().contains(edit.fileId())) {
                return "SCOPE_VIOLATION: " + edit.fileId() + " is edited but not declared in affected_file_ids";
            }
            String base = proposal.baseHashes().get(edit.fileId());
            if (base == null) {
                return "STALE_PROPOSAL: no base hash declared for " + edit.fileId();
            }
            if (!base.equals(record.get().getCurrentSha256())) {
                return "STALE_PROPOSAL: " + edit.fileId() + " was computed from " + base + " but now hashes to "
                        + record.get().getCurrentSha256();
            }
            if (proposal.capability() == ChangeProposal.Capability.MIGRATION && path.endsWith(".java")
                    && proposal.affectedSymbolIds().isEmpty()) {
                // execution-level authority covers no per-proposal human review, so the symbol scope must be declared
                return "SCOPE_VIOLATION: migration edit of " + path + " declares no affected symbols or program units";
            }
            if (("MODIFY".equals(op) || "RENAME".equals(op)) && edit.newContent() != null) {
                String symbolDenial = symbolScope(proposal, edit, record.get());
                if (symbolDenial != null) {
                    return symbolDenial;
                }
                int changed = changedLines(read(record.get().getCurrentPath()), edit.newContent());
                if (changed > session.policy.mutation().maxChangedLinesPerFile()) {
                    return "BUDGET_EXCEEDED: " + changed + " changed lines in " + path + " exceed the budget "
                            + session.policy.mutation().maxChangedLinesPerFile();
                }
            }
        }
        return null;
    }

    /**
     * Every changed line that falls inside a symbol must fall inside a declared SYMBOL_ID, or inside
     * a symbol of a declared PROGRAM_UNIT_ID. Lines outside every symbol (package, imports, class
     * annotations) are allowed; they carry no behaviour of their own.
     */
    private String symbolScope(ChangeProposal proposal, ChangeProposal.FileEdit edit, FileRecord record) {
        if (proposal.affectedSymbolIds().isEmpty() || session.identity == null) {
            return null;
        }
        String before = read(record.getCurrentPath());
        Set<Integer> changedOldLines = changedOldLineNumbers(before, edit.newContent());
        Set<String> touched = new LinkedHashSet<>();
        for (int line : changedOldLines) {
            session.identity.symbolAt(record.getFileId(), line).ifPresent(s -> touched.add(s.symbolId));
        }
        for (String symbolId : touched) {
            SymbolRecord symbol = session.identity.symbols.get(symbolId);
            boolean declared = proposal.affectedSymbolIds().contains(symbolId)
                    || symbol != null && proposal.affectedSymbolIds().contains(symbol.programUnitId);
            if (!declared) {
                return "SCOPE_VIOLATION: the patch changes " + symbolId + " (" + (symbol == null ? "?" : symbol.fqn)
                        + "), which the proposal does not declare in affected_symbol_ids";
            }
        }
        return null;
    }

    private record AuthorizationResult(String denial, boolean awaiting, String decisionId) {
    }

    private AuthorizationResult authorize(Authorization authorization, ChangeProposal proposal) {
        Optional<Decision> latest = session.approvals.latestForProposal(proposal.proposalId());
        if (latest.isPresent()) {
            Decision d = latest.get();
            if (session.approvals.verifyIntegrity().contains(d.decisionId())) {
                return new AuthorizationResult("AUTHORIZATION_MISSING: decision " + d.decisionId()
                        + " failed its integrity check and cannot authorize anything", false, d.decisionId());
            }
            if (!d.approved()) {
                return new AuthorizationResult(d.selected() + "_BY_DECISION: " + d.decisionId() + " (" + d.actor() + ": "
                        + d.rationale() + ")", false, d.decisionId());
            }
            if (!proposal.proposalHash().equals(d.proposalHash())) {
                return new AuthorizationResult("STALE_APPROVAL: " + d.decisionId() + " approved proposal hash "
                        + d.proposalHash() + " but the proposal now hashes to " + proposal.proposalHash(), false, d.decisionId());
            }
            if (!session.baselineSeal().equals(d.baselineSeal())) {
                return new AuthorizationResult("STALE_APPROVAL: " + d.decisionId() + " was given against baseline "
                        + d.baselineSeal() + ", not the sealed baseline " + session.baselineSeal(), false, d.decisionId());
            }
            return new AuthorizationResult(null, false, d.decisionId());
        }
        // execution-level authority: deterministic migration rules inside the frozen, human-authorized plan
        if (proposal.capability() == ChangeProposal.Capability.MIGRATION
                && EXECUTION_AUTHORIZABLE.contains(proposal.providerType()) && !proposal.llmAuthored()) {
            Decision exec = authorization.executionDecision();
            if (exec == null) {
                return new AuthorizationResult("AUTHORIZATION_MISSING: no human execution decision authorizes migration",
                        false, null);
            }
            boolean migrationAuthorized = exec.type() == Decision.DecisionType.EXECUTION_STRATEGY
                    && Decision.ExecutionStrategy.valueOf(exec.selected()).includesMigration()
                    || exec.type() == Decision.DecisionType.POST_SECURITY_MIGRATION
                    && Decision.MigrationChoice.PROCEED.name().equals(exec.selected());
            if (!migrationAuthorized) {
                return new AuthorizationResult("AUTHORIZATION_MISSING: decision " + exec.decisionId() + " ("
                        + exec.selected() + ") does not authorize migration", false, exec.decisionId());
            }
            if (authorization.planHash() == null || !authorization.planHash().equals(session.record.migrationPlanHash)) {
                return new AuthorizationResult("STALE_PROPOSAL: the migration plan changed since it was frozen", false,
                        exec.decisionId());
            }
            String rule = proposal.provenance() == null ? null : proposal.provenance().ruleId();
            if (rule == null || !authorization.allowedRuleIds().contains(rule)) {
                return new AuthorizationResult("AUTHORIZATION_MISSING: rule " + rule
                        + " is not in the frozen plan's allowlist " + authorization.allowedRuleIds(), false, exec.decisionId());
            }
            if (session.policy.migration().planRequiresApproval()) {
                Optional<Decision> planApproval = session.approvals.latest(Decision.DecisionType.MIGRATION_PLAN_APPROVAL);
                if (planApproval.isEmpty() || !planApproval.get().approved()
                        || !authorization.planHash().equals(planApproval.get().planHash())) {
                    return new AuthorizationResult("AUTHORIZATION_MISSING: policy requires an approved migration plan", true,
                            exec.decisionId());
                }
            }
            return new AuthorizationResult(null, false, exec.decisionId());
        }
        return new AuthorizationResult("AUTHORIZATION_MISSING: " + proposal.capability() + " proposal from "
                + proposal.providerType() + " requires a human approval bound to proposal hash " + proposal.proposalHash()
                + "; a missing decision is not approval", true, null);
    }

    // ------------------------------------------------------------------ write

    private Outcome write(ChangeProposal proposal, String decisionId) {
        String batch = String.format("BATCH-%04d-%s", ++session.record.batchCounter, proposal.proposalId());
        session.saveRecord();
        // pre-batch checkpoint: a proposal is atomic. If Bootshift applies part of it, the rest is rolled back.
        String preCheckpoint = "pre/" + session.layout.runId() + "/" + batch;
        boolean preOk = true;
        try {
            scm.checkpoint(session.layout.workspace(), session.layout.checkpointGit(), preCheckpoint,
                    "pre-batch checkpoint for " + batch);
        } catch (RuntimeException e) {
            preOk = false;
        }
        List<TransformationPort.ProposedChange> changes = new ArrayList<>();
        Set<String> fileIds = new LinkedHashSet<>(proposal.affectedFileIds());
        Set<String> prefixes = new LinkedHashSet<>();
        boolean create = false;
        boolean delete = false;
        boolean rename = false;
        for (ChangeProposal.FileEdit edit : proposal.edits()) {
            String op = edit.operation() == null ? "MODIFY" : edit.operation();
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("proposal_id", proposal.proposalId());
            if (edit.fileId() != null && proposal.baseHashes().containsKey(edit.fileId())) {
                attributes.put("base_hash", proposal.baseHashes().get(edit.fileId()));
            }
            if (edit.splitFrom() != null) {
                attributes.put("split_from", edit.splitFrom());
            }
            if (!edit.mergedFrom().isEmpty()) {
                attributes.put("merged_from", String.join(",", edit.mergedFrom()));
            }
            switch (op) {
                case "CREATE" -> {
                    create = true;
                    prefixes.add(edit.path());
                }
                case "DELETE" -> delete = true;
                case "RENAME" -> {
                    rename = true;
                    prefixes.add(edit.newPath());
                }
                default -> {
                }
            }
            edit.mergedFrom().forEach(src -> session.fileRegistry.byPath(src).ifPresent(r -> fileIds.add(r.getFileId())));
            changes.add(new TransformationPort.ProposedChange(edit.path(), edit.newPath(), op, edit.newContent(),
                    proposal.reason(), proposal.provenance() == null ? null : proposal.provenance().ruleId(),
                    concat(proposal.knowledgeRefs(), proposal.evidenceRefs()),
                    concat(proposal.findingRefs(), proposal.migrationRefs()), attributes));
        }
        com.bootshift.ports.mutation.MutationPort.Authorization bootshiftAuth =
                new com.bootshift.ports.mutation.MutationPort.Authorization(batch,
                        proposal.capability() + "/" + proposal.provider(), fileIds, prefixes, proposal.knowledgeRefs(),
                        concat(proposal.findingRefs(), proposal.migrationRefs()),
                        session.policy.mutation().maxFilesPerBatch(), session.policy.mutation().maxChangedLinesPerFile(),
                        create, delete, rename);
        com.bootshift.ports.mutation.MutationPort.BatchOutcome batchOutcome = delegate.apply(bootshiftAuth, changes,
                new ChangeEvent.Provider(proposal.providerType().name(), proposal.provider(), proposal.providerVersion()));

        long applied = batchOutcome.outcomes().stream().filter(o -> o.status() == ChangeEvent.Status.APPLIED).count();
        if (applied > 0 && applied < changes.size()) {
            // partial application of an atomic proposal: roll back to the pre-batch checkpoint
            String reasons = batchOutcome.outcomes().stream().filter(o -> o.status() != ChangeEvent.Status.APPLIED)
                    .map(o -> o.reason()).reduce((a, b) -> a + "; " + b).orElse("");
            session.record.proposalStatus.put(proposal.proposalId(), ProposalStore.Status.REVERTED.name());
            session.saveRecord();
            if (preOk) {
                delegate.revertTo(preCheckpoint, "Proposal " + proposal.proposalId() + " is atomic; partial application rolled back: " + reasons);
            }
            // The in-memory registry shared with Bootshift's gateway saw the partial batch. The persisted registry
            // (last successful batch) matches the rolled-back workspace, so the step stops here and a resume reloads it.
            throw new HarnessOutcomeException(OutcomeCategory.FAILURE, "Proposal " + proposal.proposalId()
                    + " was partially applied and rolled back to " + preCheckpoint + (preOk ? "" : " (pre-batch checkpoint "
                    + "unavailable: workspace needs manual inspection)") + ": " + reasons);
        }
        if (applied == 0) {
            String reasons = batchOutcome.outcomes().stream().map(o -> o.reason()).reduce((a, b) -> a + "; " + b).orElse("");
            session.record.proposalStatus.put(proposal.proposalId(), ProposalStore.Status.REJECTED.name());
            session.saveRecord();
            lineageRejection(proposal, decisionId, "Bootshift gateway refused: " + reasons);
            return new Outcome(proposal.proposalId(), ProposalSink.Status.REJECTED, reasons, List.of(), List.of(), null);
        }

        // identity: FILE_ID was reattached by Bootshift's gateway; continue below the file
        List<IdentitySynchronizer.AppliedFileChange> appliedChanges = new ArrayList<>();
        List<String> changeIds = new ArrayList<>();
        List<String> changedFiles = new ArrayList<>();
        for (int i = 0; i < batchOutcome.outcomes().size(); i++) {
            var outcome = batchOutcome.outcomes().get(i);
            ChangeProposal.FileEdit edit = proposal.edits().get(i);
            changeIds.add(outcome.changeId());
            changedFiles.add(outcome.fileId());
            List<String> mergedIds = new ArrayList<>();
            edit.mergedFrom().forEach(src -> session.fileRegistry.all().stream()
                    .filter(r -> src.equals(r.getCurrentPath()) || src.equals(r.getBaselinePath()))
                    .forEach(r -> mergedIds.add(r.getFileId())));
            appliedChanges.add(new IdentitySynchronizer.AppliedFileChange(outcome.fileId(), outcome.changeId(),
                    edit.operation() == null ? "MODIFY" : edit.operation(),
                    edit.splitFrom() == null ? null : session.fileRegistry.byPath(edit.splitFrom())
                            .map(FileRecord::getFileId).orElse(edit.splitFrom()), mergedIds));
        }
        IdentitySynchronizer.SyncResult sync = identitySync.sync(appliedChanges, proposal.hints(), batch);

        // lineage: who, why, which evidence, which decision, which identities
        for (int i = 0; i < batchOutcome.outcomes().size(); i++) {
            var outcome = batchOutcome.outcomes().get(i);
            ChangeProposal.FileEdit edit = proposal.edits().get(i);
            String changeId = outcome.changeId();
            List<String> created = session.identity.statements.values().stream()
                    .filter(s -> changeId.equals(s.createdByChange)).map(s -> s.statementId).toList();
            List<String> deleted = session.identity.statements.values().stream()
                    .filter(s -> changeId.equals(s.deletedByChange)).map(s -> s.statementId).toList();
            List<String> changed = session.identity.statements.values().stream()
                    .filter(s -> s.changeIds.contains(changeId) && !changeId.equals(s.createdByChange)
                            && !changeId.equals(s.deletedByChange)).map(s -> s.statementId).toList();
            List<String> symbols = session.identity.symbols.values().stream()
                    .filter(s -> s.changeIds.contains(changeId)).map(s -> s.symbolId).toList();
            Decision decision = decisionId == null ? null : session.approvals.find(decisionId).orElse(null);
            session.lineage.append(new LineageLedger.Entry(0, "CHANGE", changeId, proposal.proposalId(), decisionId,
                    proposal.capability().name(), proposal.provider(), decision == null ? null : decision.actor(),
                    proposal.reason(), proposal.findingRefs(), proposal.migrationRefs(),
                    concat(proposal.evidenceRefs(), sync.evidenceRefs()), outcome.fileId(), edit.path(),
                    edit.newPath() == null ? edit.path() : edit.newPath(), outcome.beforeSha256(), outcome.afterSha256(),
                    symbols, changed, created, deleted, List.of(), "APPLIED", null));
        }

        // checkpoint record binding source state, ledger head and identity
        CheckpointStore.Checkpoint checkpoint = session.checkpoints.save(new CheckpointStore.Checkpoint(null,
                session.layout.runId(), batch, session.record.machine.current.name(), batchOutcome.checkpointRef(),
                session.ledger.head(), session.ledger.size(), session.identity.contentHash(),
                session.fileRegistry.contentManifestHash(), List.of(proposal.proposalId()), Instant.now().toString()));

        session.record.proposalStatus.put(proposal.proposalId(), ProposalStore.Status.APPLIED.name());
        session.record.proposalChanges.put(proposal.proposalId(), changeIds);
        session.saveRecord();

        session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                "Proposal " + proposal.proposalId() + " applied through the Mutation Gateway: " + changeIds,
                proposal.reason(), String.join(",", changedFiles), concat(changeIds, changedFiles),
                "authorized by " + (decisionId == null ? "none" : decisionId), EvidenceRecord.Reliability.VERIFIED, null,
                "mutations/patches", null, "kernel.mutation-gateway");

        postBatch.accept(batch, changedFiles);

        // runtime bypass detection
        List<String> bypass = delegate.detectBypass();
        if (!bypass.isEmpty()) {
            session.record.notes.add("BYPASS DETECTED after " + batch + ": " + bypass);
            session.saveRecord();
            session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Mutation bypass detected after " + batch,
                    String.join(" | ", bypass), null, List.of(), "FileMutationGateway.detectBypass",
                    EvidenceRecord.Reliability.VERIFIED, null, null, null, "kernel.mutation-gateway");
        }
        return new Outcome(proposal.proposalId(), ProposalSink.Status.APPLIED, sync.unsynchronized().isEmpty() ? null
                : "identity not re-synchronised for " + sync.unsynchronized(), changeIds, changedFiles,
                checkpoint.checkpointId());
    }

    @Override
    public List<String> detectBypass() {
        return delegate.detectBypass();
    }

    private Outcome refuse(ChangeProposal proposal, ProposalSink.Status status, String reason, String decisionId) {
        session.ledger.append(new ChangeEvent()
                .setRunId(session.layout.runId())
                .setEdgeId(proposal.proposalId())
                .setAgent(proposal.capability() + "/" + proposal.provider())
                .setProvider(new ChangeEvent.Provider(proposal.providerType().name(), proposal.provider(),
                        proposal.providerVersion()))
                .setRecipeId(proposal.provenance() == null ? null : proposal.provenance().ruleId())
                .setFileId(proposal.affectedFileIds().isEmpty() ? null : proposal.affectedFileIds().get(0))
                .setOperation(ChangeEvent.Operation.MODIFY)
                .setStatus(ChangeEvent.Status.REJECTED)
                .setRejectionReason(reason));
        ProposalStore.Status stored = switch (status) {
            case STALE -> ProposalStore.Status.STALE;
            case AWAITING_APPROVAL -> ProposalStore.Status.AWAITING_APPROVAL;
            default -> reason.startsWith("DEFERRED") ? ProposalStore.Status.DEFERRED : ProposalStore.Status.REJECTED;
        };
        session.record.proposalStatus.put(proposal.proposalId(), stored.name());
        session.saveRecord();
        lineageRejection(proposal, decisionId, reason);
        return new Outcome(proposal.proposalId(), status, reason, List.of(), List.of(), null);
    }

    private void lineageRejection(ChangeProposal proposal, String decisionId, String reason) {
        session.lineage.append(new LineageLedger.Entry(0, "REJECTION", null, proposal.proposalId(), decisionId,
                proposal.capability().name(), proposal.provider(), null, reason, proposal.findingRefs(),
                proposal.migrationRefs(), proposal.evidenceRefs(), proposal.affectedFileIds().isEmpty() ? null
                : proposal.affectedFileIds().get(0), null, null, null, null, proposal.affectedSymbolIds(), List.of(),
                List.of(), List.of(), List.of(), "REJECTED", null));
    }

    /** Records validation against applied changes (VALIDATED / FAILED_VALIDATION), in both ledgers. */
    public void recordValidation(String proposalId, boolean passed, String validationRef, String summary) {
        List<String> changeIds = session.record.proposalChanges.getOrDefault(proposalId, List.of());
        for (String changeId : changeIds) {
            session.ledger.append(new ChangeEvent()
                    .setRunId(session.layout.runId())
                    .setEdgeId(proposalId)
                    .setAgent("kernel.validation")
                    .setProvider(new ChangeEvent.Provider("VALIDATION", "unified-validation", "1.0"))
                    .setOperation(ChangeEvent.Operation.MODIFY)
                    .setStatus(passed ? ChangeEvent.Status.VALIDATED : ChangeEvent.Status.FAILED_VALIDATION)
                    .setImpactRefs(List.of(changeId, validationRef))
                    .setRejectionReason(summary));
            session.lineage.append(new LineageLedger.Entry(0, "VALIDATION", changeId, proposalId, null, null, null, null,
                    summary, List.of(), List.of(), List.of(), null, null, null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(validationRef), passed ? "VALIDATED" : "FAILED_VALIDATION", null));
        }
        session.record.proposalStatus.put(proposalId, passed ? ProposalStore.Status.VALIDATED.name()
                : ProposalStore.Status.FAILED_VALIDATION.name());
        session.saveRecord();
    }

    // ------------------------------------------------------------------ helpers

    private String read(String relativePath) {
        try {
            Path file = session.layout.workspace().resolve(relativePath);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Old-side line numbers (1-based) deleted or replaced by the new content (LCS line diff). */
    static Set<Integer> changedOldLineNumbers(String before, String after) {
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        Set<Integer> changed = new LinkedHashSet<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                changed.add(i + 1);
                i++;
            } else {
                // insertion before old line i+1: attribute it to the enclosing old line
                changed.add(Math.max(1, i));
                j++;
            }
        }
        while (i < a.size()) {
            changed.add(++i);
        }
        if (j < b.size()) {
            changed.add(Math.max(1, a.size()));
        }
        return changed;
    }

    static int changedLines(String before, String after) {
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));
        int max = Math.max(a.size(), b.size());
        int changed = 0;
        for (int i = 0; i < max; i++) {
            String left = i < a.size() ? a.get(i) : null;
            String right = i < b.size() ? b.get(i) : null;
            if (left == null || !left.equals(right)) {
                changed++;
            }
        }
        return changed;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            b.stream().filter(x -> !all.contains(x)).forEach(all::add);
        }
        return all;
    }

}
