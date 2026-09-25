package com.mars.harness.kernel.ports.evidence;

import com.mars.harness.kernel.core.evidence.EvidenceRecord;

import java.util.List;
import java.util.Optional;

/** The run's evidence plane (spec §27 {@code EvidenceStore}). Append-only and hash-chained. */
public interface EvidenceStore {

    EvidenceRecord record(EvidenceRecord.EvidenceKind kind, String summary, String observed, String where,
                          List<String> subjects, String basis, EvidenceRecord.Reliability reliability, String asOf,
                          String artifactRef, String artifactSha256, String producer);

    Optional<EvidenceRecord> get(String evidenceId);

    List<EvidenceRecord> all();
}
