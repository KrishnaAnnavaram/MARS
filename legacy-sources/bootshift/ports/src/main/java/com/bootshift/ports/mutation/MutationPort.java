package com.bootshift.ports.mutation;

import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.ports.transformation.TransformationPort;

import java.util.List;
import java.util.Set;

/**
 * The single authorized writer of application source (R13).
 *
 * <p>Everything a transformation or repair stage wants to change must arrive here as a proposal. The
 * gateway verifies the baseline seal, resolves FILE_ID, checks the change against the frozen plan
 * and impact scope, hashes before and after, updates the registry, writes the patch, appends the
 * ledger entry and creates a checkpoint.
 */
public interface MutationPort {

    /** Authorization envelope proving the change was planned rather than improvised. */
    record Authorization(String edgeId, String agent, Set<String> authorizedFileIds,
                         Set<String> authorizedPathPrefixes, List<String> knowledgeRefs,
                         List<String> impactRefs, int maxFiles, int maxChangedLines,
                         boolean allowCreate, boolean allowDelete, boolean allowRename) {
    }

    record MutationOutcome(String changeId, String fileId, ChangeEvent.Status status, String reason,
                           String beforeSha256, String afterSha256, String patchRef) {
    }

    record BatchOutcome(List<MutationOutcome> outcomes, int applied, int rejected, int failed,
                        String checkpointRef) {
    }

    /** Applies a batch of proposals atomically with respect to checkpointing. */
    BatchOutcome apply(Authorization authorization, List<TransformationPort.ProposedChange> proposals,
                       ChangeEvent.Provider provider);

    /** Reverts every change made since the named checkpoint. */
    void revertTo(String checkpointName, String reason);

    /**
     * Detects writes to the migration workspace that did not come through the gateway, by comparing
     * on-disk content against the hashes the registry believes are current.
     */
    List<String> detectBypass();
}
