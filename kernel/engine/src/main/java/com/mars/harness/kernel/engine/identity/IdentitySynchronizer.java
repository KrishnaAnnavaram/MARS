package com.mars.harness.kernel.engine.identity;

import com.bootshift.core.identity.FileRecord;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.identity.IdentityReattacher;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.identity.ModuleRecord;
import com.mars.harness.kernel.core.identity.ProviderHints;
import com.mars.harness.kernel.core.identity.ReattachmentReport;
import com.mars.harness.kernel.core.identity.observe.CodeObservation;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.analysis.CodeModelPort;
import com.mars.harness.kernel.ports.build.BuildModelView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps sub-file identity synchronised with the workspace (spec §20).
 *
 * <p>It runs after <em>every</em> authorised mutation batch, never only at the end of a run, so a
 * crash costs at most the current batch. FILE_ID reattachment is Bootshift's FileMutationGateway
 * and FileRegistry. This class continues the chain below the file: program unit, then symbol, then
 * statement.
 *
 * <p>A file that fails to parse after a mutation is not re-synchronised. Its identities stay at
 * their last known state and the gap is published as UNKNOWN evidence. A parse failure is never
 * taken to mean every statement was deleted.
 */
public final class IdentitySynchronizer {

    /** A file-level change the gateway applied. */
    public record AppliedFileChange(String fileId, String changeId, String operation, String splitFrom,
                                    List<String> mergedFromFileIds) {
        public AppliedFileChange {
            mergedFromFileIds = mergedFromFileIds == null ? List.of() : List.copyOf(mergedFromFileIds);
        }
    }

    public record SyncResult(ReattachmentReport report, List<String> unsynchronized, List<String> evidenceRefs) {
    }

    private final RunSession session;
    private final CodeModelPort codeModel;

    public IdentitySynchronizer(RunSession session, CodeModelPort codeModel) {
        this.session = session;
        this.codeModel = codeModel;
    }

    /** Phase 1: allocate MODULE_ID for every module and sub-file identities for every parseable file. */
    public List<String> baseline(BuildModelView buildModel) {
        IdentityRegistry registry = session.identity;
        if (buildModel != null) {
            for (BuildModelView.ModuleBuild module : buildModel.modules()) {
                registry.registerModule(module.name(), module.path(), module.buildFile(), buildModel.buildSystem());
            }
        }
        if (registry.modules.isEmpty()) {
            registry.registerModule(".", ".", null, "UNKNOWN");
        }
        List<String> gaps = new ArrayList<>();
        for (FileRecord file : session.fileRegistry.active()) {
            if (!codeModel.supports(file.getCurrentPath())) {
                continue;
            }
            Optional<String> content = read(file.getCurrentPath());
            if (content.isEmpty()) {
                gaps.add(file.getFileId() + " unreadable");
                continue;
            }
            CodeObservation observation = codeModel.observe(file.getFileId(), file.getCurrentPath(),
                    moduleId(file.getCurrentPath()), content.get());
            if (!observation.issues().isEmpty()) {
                gaps.add(file.getFileId() + " (" + file.getCurrentPath() + "): " + String.join("; ", observation.issues()));
                continue;
            }
            registry.allocateBaseline(observation);
        }
        return gaps;
    }

    /** After a batch: re-observe the changed files and reattach unit, symbol and statement identity. */
    public SyncResult sync(List<AppliedFileChange> applied, ProviderHints hints, String label) {
        List<IdentityReattacher.FileChange> changes = new ArrayList<>();
        Map<String, String> changeByFile = new LinkedHashMap<>();
        List<String> unsynchronized = new ArrayList<>();
        for (AppliedFileChange change : applied) {
            changeByFile.put(change.fileId(), change.changeId());
            change.mergedFromFileIds().forEach(f -> changeByFile.putIfAbsent(f, change.changeId()));
            if ("DELETE".equals(change.operation())) {
                changes.add(IdentityReattacher.FileChange.deletedFile(change.fileId()));
                continue;
            }
            FileRecord record = session.fileRegistry.byId(change.fileId()).orElse(null);
            if (record == null || !codeModel.supports(record.getCurrentPath())) {
                continue;
            }
            Optional<String> content = read(record.getCurrentPath());
            if (content.isEmpty()) {
                unsynchronized.add(change.fileId() + " unreadable after mutation");
                continue;
            }
            CodeObservation observation = codeModel.observe(record.getFileId(), record.getCurrentPath(),
                    moduleId(record.getCurrentPath()), content.get());
            if (!observation.issues().isEmpty()) {
                unsynchronized.add(change.fileId() + " (" + record.getCurrentPath() + ") "
                        + String.join("; ", observation.issues()));
                continue;
            }
            List<String> lineage = new ArrayList<>(List.of(change.fileId()));
            if (change.splitFrom() != null) {
                lineage.add(change.splitFrom());
            }
            lineage.addAll(change.mergedFromFileIds());
            changes.add(new IdentityReattacher.FileChange(change.fileId(), lineage, observation, false,
                    change.mergedFromFileIds(), change.splitFrom()));
        }
        ProviderHints normalized = normalizeHints(hints);
        String defaultChange = applied.isEmpty() ? null : applied.get(0).changeId();
        ReattachmentReport report = IdentityReattacher.reattach(session.identity,
                new IdentityReattacher.Request(defaultChange, changes, normalized, changeByFile),
                session.policy.identity().toReattachmentPolicy());
        session.saveIdentity();
        session.artifacts.writeJson("identity", "reattachment-" + label + ".json", report);

        List<String> evidence = new ArrayList<>();
        long reattached = report.entries.stream().filter(e -> ReattachmentReport.REATTACHED.equals(e.outcome())).count();
        long allocated = report.entries.stream().filter(e -> ReattachmentReport.ALLOCATED.equals(e.outcome())).count();
        long retired = report.entries.stream().filter(e -> ReattachmentReport.DELETED.equals(e.outcome())
                || ReattachmentReport.MERGED_AWAY.equals(e.outcome())).count();
        evidence.add(session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                "Identity reattachment after batch " + label + ": " + reattached + " reattached, " + allocated
                        + " allocated, " + retired + " retired, " + report.uncertain().size() + " uncertain",
                null, "identity/reattachment-" + label + ".json", new ArrayList<>(changeByFile.values()),
                "IdentityReattacher evidence hierarchy (spec 7.4), policy " + session.policy.policyVersion(),
                EvidenceRecord.Reliability.DERIVED, null, "identity/reattachment-" + label + ".json", null,
                "kernel.identity").evidenceId());
        if (!unsynchronized.isEmpty()) {
            evidence.add(session.evidence.record(EvidenceRecord.EvidenceKind.UNKNOWN,
                    "Identity not re-synchronised for " + unsynchronized.size() + " file(s); identities left at last known state",
                    String.join(" | ", unsynchronized), null, List.of(), "parse failure is not deletion",
                    EvidenceRecord.Reliability.UNKNOWN, null, null, null, "kernel.identity").evidenceId());
        }
        return new SyncResult(report, unsynchronized, evidence);
    }

    private ProviderHints normalizeHints(ProviderHints hints) {
        if (hints == null) {
            return ProviderHints.none();
        }
        Map<String, String> statements = new LinkedHashMap<>();
        hints.statementRewrites().forEach((id, text) -> statements.put(id, codeModel.normalizeStatement(text)));
        return new ProviderHints(hints.unitRenames(), hints.symbolRenames(), statements);
    }

    private String moduleId(String path) {
        return session.identity.moduleForPath(path).map(ModuleRecord::id).orElse(null);
    }

    private Optional<String> read(String relativePath) {
        Path file = session.layout.workspace().resolve(relativePath);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
