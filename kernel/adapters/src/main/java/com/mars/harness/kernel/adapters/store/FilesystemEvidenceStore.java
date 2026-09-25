package com.mars.harness.kernel.adapters.store;

import com.mars.harness.kernel.core.evidence.EvidenceLog;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;

import java.util.List;
import java.util.Optional;

/** Evidence plane backed by the hash-chained {@link EvidenceLog}. */
public final class FilesystemEvidenceStore implements EvidenceStore {

    private final EvidenceLog log;

    public FilesystemEvidenceStore(RunLayout layout) {
        this.log = EvidenceLog.open(layout.evidenceLog(), layout.runId());
    }

    @Override
    public EvidenceRecord record(EvidenceRecord.EvidenceKind kind, String summary, String observed, String where,
                                 List<String> subjects, String basis, EvidenceRecord.Reliability reliability,
                                 String asOf, String artifactRef, String artifactSha256, String producer) {
        return log.record(kind, summary, observed, where, subjects, basis, reliability, asOf, artifactRef,
                artifactSha256, producer);
    }

    @Override
    public Optional<EvidenceRecord> get(String evidenceId) {
        return log.get(evidenceId);
    }

    @Override
    public List<EvidenceRecord> all() {
        return log.all();
    }

    public String head() {
        return log.head();
    }
}
