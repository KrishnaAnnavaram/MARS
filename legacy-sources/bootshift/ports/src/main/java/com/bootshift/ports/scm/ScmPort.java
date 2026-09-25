package com.bootshift.ports.scm;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Source control operations the harness needs.
 *
 * <p>Two intake shapes must both work (spec section 5): a Git-backed repository, and a plain
 * directory such as the current {@code ./src}. In the plain-directory case the adapter creates an
 * internal checkpoint repository inside the external run workspace; the user input path is never
 * required to contain {@code .git} and is never written to.
 */
public interface ScmPort {

    /** Provenance of the input as it was received. */
    record SourceProvenance(String kind, String remote, String branch, String commitSha,
                            String treeSha, String contentManifestHash, String capturedAt) {
    }

    /** A rename the SCM itself reports, which outranks similarity guessing. */
    record RenameRecord(String fromPath, String toPath, int similarityScore) {
    }

    /** A checkpoint (tag or ref) inside the internal migration history. */
    record Checkpoint(String name, String commitSha, String createdAt) {
    }

    boolean isGitBacked(Path root);

    /** Captures provenance without mutating the input. */
    SourceProvenance captureProvenance(Path sourceRoot);

    /** Creates an immutable copy of the source at the destination and returns its manifest hash. */
    String snapshot(Path sourceRoot, Path destination, List<String> excludes);

    /** Initializes the internal checkpoint repository from an already-created snapshot. */
    void initCheckpointRepository(Path workTree, Path gitDir, String message);

    Checkpoint checkpoint(Path workTree, Path gitDir, String name, String message);

    Optional<Checkpoint> findCheckpoint(Path workTree, Path gitDir, String name);

    void rollbackTo(Path workTree, Path gitDir, String checkpointName);

    List<RenameRecord> detectRenames(Path workTree, Path gitDir, String fromRef, String toRef);

    /** Unified diff between two checkpoints, used to export migration.patch. */
    String diff(Path workTree, Path gitDir, String fromRef, String toRef);

    /** One patch file per change, used to export patch-series/. */
    List<String> formatPatchSeries(Path workTree, Path gitDir, String fromRef, String toRef);

    String currentTreeHash(Path workTree, Path gitDir);
}
