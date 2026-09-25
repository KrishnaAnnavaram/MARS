package com.mars.harness.kernel.adapters.store;

import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.ports.checkpoint.CheckpointStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Checkpoint records in {@code checkpoints/CKPT-*.json}, published atomically. */
public final class FilesystemCheckpointStore implements CheckpointStore {

    private final RunLayout layout;
    private final FilesystemArtifactStore artifacts;

    public FilesystemCheckpointStore(RunLayout layout) {
        this.layout = layout;
        this.artifacts = new FilesystemArtifactStore(layout);
    }

    @Override
    public Checkpoint save(Checkpoint checkpoint) {
        Checkpoint stored = checkpoint.checkpointId() != null ? checkpoint
                : new Checkpoint(HarnessIds.allocate(HarnessIds.Kind.CHECKPOINT), checkpoint.runId(), checkpoint.label(),
                checkpoint.phase(), checkpoint.scmCommit(), checkpoint.ledgerHead(), checkpoint.ledgerSize(),
                checkpoint.identityRegistryHash(), checkpoint.fileRegistrySeal(), checkpoint.appliedProposalIds(),
                checkpoint.createdAt());
        artifacts.writeJsonOnce("checkpoints", stored.checkpointId() + ".json", stored);
        return stored;
    }

    @Override
    public List<Checkpoint> all() {
        Path dir = layout.area("checkpoints");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Checkpoint> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> result.add(KernelJson.read(p, Checkpoint.class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(Checkpoint::checkpointId));
        return result;
    }

    @Override
    public Optional<Checkpoint> latest() {
        List<Checkpoint> all = all();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }
}
