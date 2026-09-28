package com.mars.harness.controlcenter.api.dto;

import java.util.List;

/** The evidence plane and the run's supporting files. */
public final class EvidenceDtos {

    private EvidenceDtos() {
    }

    /** @param chainIndex 1-based position in the hash chain ({@code provenance/evidence.jsonl}) */
    public record EvidenceView(String evidenceId, int chainIndex, String kind, String summary, String observed, String where,
                               List<String> subjects, String basis, String reliability, String observedAt, String asOf,
                               String artifactRef, String artifactSha256, String producer, String phaseGroup) {
    }

    /**
     * @param chainIntact the whole chain recomputed from disk; says nothing about who produced a record
     */
    public record EvidencePage(List<EvidenceView> evidence, int total, int offset, int limit, boolean chainIntact,
                               List<String> chainViolations, java.util.Map<String, Integer> byKind,
                               java.util.Map<String, Integer> byProducer) {
    }

    public record ArtifactEntry(String path, long size, String modified, String kind) {
    }

    public record LogFile(String path, long size, String modified, String origin) {
    }

    /**
     * @param level parsed from the tool's own line prefix ([INFO], [ERROR], …) for filtering only;
     *              logs are not the domain event model
     */
    public record LogLine(int number, String level, String text) {
    }

    public record LogChunk(String path, int fromLine, int totalLines, List<LogLine> lines, boolean truncated) {
    }
}
