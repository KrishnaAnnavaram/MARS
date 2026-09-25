package com.mars.harness.kernel.ports.evidence;

import java.nio.file.Path;

/**
 * Where capabilities publish their artifacts. Every write lands inside the run directory under a
 * named area ({@code plans/}, {@code discovery/security/}, …). This is the only file-writing
 * facility a capability has. It cannot reach tracked source.
 */
public interface ArtifactStore {

    /** Writes JSON atomically (temp file, then move) and returns the artifact path. */
    Path writeJson(String area, String name, Object payload);

    Path writeText(String area, String name, String text);

    /** Write-once: refuses to overwrite an existing artifact (decisions, sealed records). */
    Path writeJsonOnce(String area, String name, Object payload);

    /**
     * Copies an input file (a findings workbook, a research analysis, a probe file) into the run,
     * byte for byte, so the run's evidence never depends on a file that may later move or change.
     */
    Path importFile(String area, Path source);

    Path path(String area, String name);

    boolean exists(String area, String name);

    String sha256(Path artifact);
}
