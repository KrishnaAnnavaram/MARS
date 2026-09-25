package com.mars.harness.kernel.core.change;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.identity.ProviderHints;

import java.util.List;
import java.util.Map;

/**
 * The single contract through which anything may ask to change tracked source (spec §17).
 *
 * <p>The kernel does not care which provider produced it: OpenRewrite, a deterministic migration
 * rule, a dependency upgrader, a security fixer, compiler repair, an LLM or a human's patch. It
 * checks the same things for all of them: base identity, base hashes, scope and authorization.
 *
 * <p>{@link #proposalHash()} covers every field except the hash itself, so an approval bound to it
 * becomes stale as soon as any byte of the proposal changes.
 *
 * @param strategyOnly true for a plan-level proposal with no edits yet (research and KB
 *                     strategies before a fix exists). It cannot be applied; approving it only
 *                     authorizes producing a concrete fix proposal, which needs its own approval.
 */
public record ChangeProposal(String proposalId, String runId, Capability capability, String provider,
                             ProviderType providerType, String providerVersion, String reason,
                             List<String> findingRefs, List<String> migrationRefs, List<String> evidenceRefs,
                             List<String> knowledgeRefs, List<String> affectedFileIds, List<String> affectedSymbolIds,
                             List<String> affectedStatementIds, List<FileEdit> edits, String expectedOutcome,
                             Risk risk, Map<String, String> baseHashes, String baselineSeal, String generatedAt,
                             Provenance provenance, boolean strategyOnly, ProviderHints hints,
                             String supersedes) {

    public ChangeProposal {
        findingRefs = copy(findingRefs);
        migrationRefs = copy(migrationRefs);
        evidenceRefs = copy(evidenceRefs);
        knowledgeRefs = copy(knowledgeRefs);
        affectedFileIds = copy(affectedFileIds);
        affectedSymbolIds = copy(affectedSymbolIds);
        affectedStatementIds = copy(affectedStatementIds);
        edits = edits == null ? List.of() : List.copyOf(edits);
        baseHashes = baseHashes == null ? Map.of() : Map.copyOf(baseHashes);
        hints = hints == null ? ProviderHints.none() : hints;
    }

    private static List<String> copy(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    public enum Capability { MIGRATION, SECURITY, MANUAL }

    /** LLM proposals can never be covered by an execution-level authorization. They always need approval. */
    public enum ProviderType { DETERMINISTIC_RULE, OPENREWRITE, DEPENDENCY_UPGRADER, SECURITY_FIXER, COMPILER_REPAIR, LLM, MANUAL_PATCH }

    public enum Risk { LOW, MEDIUM, HIGH }

    /**
     * One file operation. {@code newContent} is the complete replacement text, the same
     * representation Bootshift's ProposedChange uses. {@code unifiedDiff} is the reviewable view.
     */
    public record FileEdit(String fileId, String path, String newPath, String operation, String newContent,
                           String unifiedDiff, String splitFrom, List<String> mergedFrom) {
        public FileEdit {
            mergedFrom = mergedFrom == null ? List.of() : List.copyOf(mergedFrom);
        }
    }

    /**
     * Where the proposal came from. For an LLM provider, model, prompt, context and response
     * hashes are mandatory (spec §28).
     */
    public record Provenance(String producer, String ruleId, String referenceSection, String model,
                             String modelVersion, String promptHash, String contextHash, String responseHash,
                             List<String> verification) {
        public Provenance {
            verification = verification == null ? List.of() : List.copyOf(verification);
        }
    }

    @JsonIgnore
    public String proposalHash() {
        return KernelJson.hash(this);
    }

    public boolean llmAuthored() {
        return providerType == ProviderType.LLM;
    }
}
