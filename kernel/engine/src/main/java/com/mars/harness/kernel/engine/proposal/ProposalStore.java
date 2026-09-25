package com.mars.harness.kernel.engine.proposal;

import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.run.RunLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Immutable proposal records ({@code proposals/PROP-*.json}).
 *
 * <p>A proposal file is written once. Lifecycle status (PROPOSED, APPLIED, REJECTED, …) lives in
 * the run record, so the hash an approval was bound to can always be recomputed from the file
 * and compared.
 */
public final class ProposalStore {

    /** Lifecycle statuses, mirroring Bootshift's ChangeEvent statuses plus the gate states. */
    public enum Status { PROPOSED, AWAITING_APPROVAL, APPROVED, APPLIED, VALIDATED, FAILED_VALIDATION, REJECTED,
        DEFERRED, STALE, REVERTED, BLOCKED_BY_PLATFORM }

    private final RunLayout layout;

    public ProposalStore(RunLayout layout) {
        this.layout = layout;
    }

    public ChangeProposal save(ChangeProposal proposal) {
        Path file = layout.proposals().resolve(proposal.proposalId() + ".json");
        if (Files.exists(file)) {
            ChangeProposal existing = KernelJson.read(file, ChangeProposal.class);
            if (!existing.proposalHash().equals(proposal.proposalHash())) {
                throw new IllegalStateException("Proposal " + proposal.proposalId()
                        + " already exists with different content; proposals are immutable");
            }
            return existing;
        }
        try {
            Files.createDirectories(layout.proposals());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, KernelJson.pretty(proposal));
            Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(layout.proposals().resolve(proposal.proposalId() + ".sha256"), proposal.proposalHash());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save proposal " + file, e);
        }
        return proposal;
    }

    public Optional<ChangeProposal> find(String proposalId) {
        Path file = layout.proposals().resolve(proposalId + ".json");
        return Files.isRegularFile(file) ? Optional.of(KernelJson.read(file, ChangeProposal.class)) : Optional.empty();
    }

    public List<ChangeProposal> all() {
        if (!Files.isDirectory(layout.proposals())) {
            return List.of();
        }
        List<ChangeProposal> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(layout.proposals())) {
            files.filter(p -> p.getFileName().toString().matches("PROP-.*\\.json")).sorted()
                    .forEach(p -> result.add(KernelJson.read(p, ChangeProposal.class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
