package com.mars.harness.kernel.ports.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Durable checkpoints of run state (spec §27 {@code CheckpointStore}). A checkpoint binds the
 * source state (Bootshift's checkpoint commit), the ledger head, the identity registry hash and
 * the run phase. A crash costs at most the current batch.
 */
public interface CheckpointStore {

    record Checkpoint(String checkpointId, String runId, String label, String phase, String scmCommit,
                      String ledgerHead, long ledgerSize, String identityRegistryHash, String fileRegistrySeal,
                      List<String> appliedProposalIds, String createdAt) {
        public Checkpoint {
            appliedProposalIds = appliedProposalIds == null ? List.of() : List.copyOf(appliedProposalIds);
        }
    }

    Checkpoint save(Checkpoint checkpoint);

    List<Checkpoint> all();

    Optional<Checkpoint> latest();
}
