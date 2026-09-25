package com.mars.harness.kernel.engine.mutation;

import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileStatus;
import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.run.RunSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The explicit apply-to-project step (reference workflow step 10; spec §13.2: "applying final
 * result requires explicit authorized action").
 *
 * <p>This is the only code that writes the developer's original repository. It lives in the
 * mutation package on purpose, as part of the single-writer boundary. It refuses unless:
 *
 * <ul>
 *   <li>an APPLY_TO_PROJECT decision is APPROVED and bound to the current ledger head</li>
 *   <li>the verdict is not BLOCKED, NEEDS_HUMAN or INSUFFICIENT_EVIDENCE</li>
 *   <li>when migration ran, its last round was green (the reference {@code apply-migration.js}
 *       rule)</li>
 *   <li>every file it will touch still has its snapshot hash in the project; if the project
 *       moved on since the snapshot, applying would overwrite someone's work</li>
 * </ul>
 */
public final class ProjectApplier {

    public record ApplyResult(int written, int deleted, List<String> files, String decisionId) {
    }

    private ProjectApplier() {
    }

    public static ApplyResult apply(RunSession session, Path repository, Decision decision, boolean migrationLastRoundGreen) {
        if (decision == null || decision.type() != Decision.DecisionType.APPLY_TO_PROJECT || !decision.approved()) {
            throw new HarnessOutcomeException(OutcomeCategory.AUTHORIZATION_MISSING,
                    "Applying to the project requires an APPROVED apply-to-project decision");
        }
        if (!session.ledger.head().equals(decision.ledgerHead())) {
            throw new HarnessOutcomeException(OutcomeCategory.STALE_PROPOSAL, "The apply decision was bound to ledger head "
                    + decision.ledgerHead() + " but the run's ledger is now at " + session.ledger.head());
        }
        String verdict = session.record.verdict;
        if (verdict == null || List.of("BLOCKED", "NEEDS_HUMAN", "INSUFFICIENT_EVIDENCE").contains(verdict)) {
            throw new HarnessOutcomeException(OutcomeCategory.POLICY_BLOCK, "REFUSED: the run verdict is " + verdict
                    + "; only a CLEARED or PARTIAL result may be applied");
        }
        if (session.record.migrationExecuted && !migrationLastRoundGreen) {
            throw new HarnessOutcomeException(OutcomeCategory.POLICY_BLOCK,
                    "REFUSED: the last migration round was not \"passed\"");
        }
        List<FileRecord> changed = session.fileRegistry.all().stream()
                .filter(r -> !r.getChangeIds().isEmpty()).toList();
        List<String> stale = new ArrayList<>();
        for (FileRecord r : changed) {
            Path original = repository.resolve(r.getBaselinePath());
            if (r.getBaselineSha256() != null) {
                String now = sha(original);
                if (now != null && !now.equals(r.getBaselineSha256())) {
                    stale.add(r.getBaselinePath());
                }
            }
        }
        if (!stale.isEmpty()) {
            throw new HarnessOutcomeException(OutcomeCategory.STALE_PROPOSAL,
                    "REFUSED: the project changed since the snapshot at " + stale, stale);
        }
        int written = 0;
        int deleted = 0;
        List<String> files = new ArrayList<>();
        try {
            for (FileRecord r : changed) {
                Path target = repository.resolve(r.getCurrentPath()).normalize();
                if (!target.startsWith(repository.normalize())) {
                    throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Path escapes the project: " + r.getCurrentPath());
                }
                if (r.getStatus() == FileStatus.ACTIVE) {
                    Files.createDirectories(target.getParent());
                    Files.copy(session.layout.workspace().resolve(r.getCurrentPath()), target, StandardCopyOption.REPLACE_EXISTING);
                    written++;
                    files.add(r.getCurrentPath());
                    if (!r.getBaselinePath().equals(r.getCurrentPath()) && r.getBaselineSha256() != null) {
                        Files.deleteIfExists(repository.resolve(r.getBaselinePath()));
                    }
                } else if (r.getBaselineSha256() != null) {
                    Files.deleteIfExists(repository.resolve(r.getBaselinePath()));
                    deleted++;
                    files.add("deleted " + r.getBaselinePath());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Apply to project failed part-way; inspect " + repository, e);
        }
        session.lineage.append(new LineageLedger.Entry(0, "APPLY_TO_PROJECT", null, null, decision.decisionId(), null,
                "project-applier", decision.actor(), decision.rationale(), List.of(), List.of(), List.of(), null, null,
                repository.toString(), null, session.ledger.head(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "APPLIED", null));
        session.evidence.record(EvidenceRecord.EvidenceKind.DECISION, "Result applied to the project by explicit decision "
                        + decision.decisionId(), written + " written, " + deleted + " deleted", repository.toString(), files,
                "reference workflow apply step; decision-bound", EvidenceRecord.Reliability.ASSERTED, null, null, null,
                "kernel.project-applier");
        return new ApplyResult(written, deleted, files, decision.decisionId());
    }

    private static String sha(Path file) {
        try {
            return Files.isRegularFile(file) ? Hashing.sha256File(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
