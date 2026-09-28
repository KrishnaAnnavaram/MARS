package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.controlcenter.api.dto.ProposalDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.engine.ledger.LineageLedger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Proposals, their decisions and what the Mutation Gateway did with them. */
public final class ProposalProjector {

    static final int MAX_CONTENT = 256 * 1024;
    private static final Set<String> AWAITING = Set.of("PROPOSED", "AWAITING_APPROVAL");
    private static final Set<ExecutionEventType> MUTATION_EVENTS = Set.of(ExecutionEventType.MUTATION_STARTED,
            ExecutionEventType.MUTATION_APPLIED, ExecutionEventType.MUTATION_REFUSED, ExecutionEventType.MUTATION_ROLLED_BACK);

    private ProposalProjector() {
    }

    public static ProposalDtos.ProposalRow row(RunReader run, ChangeProposal p) {
        String status = run.proposalStatus(p.proposalId());
        Optional<Decision> decision = run.latestDecisionFor(p.proposalId());
        List<String> labels = p.findingRefs().stream().map(id -> run.finding(id).map(Finding::sourceFindingId).orElse(id))
                .toList();
        return new ProposalDtos.ProposalRow(p.proposalId(), p.capability().name(), p.provider(), p.providerType().name(),
                status, p.strategyOnly(), p.findingRefs(), labels,
                p.edits().stream().map(e -> e.newPath() == null ? e.path() : e.path() + " → " + e.newPath()).toList(),
                p.edits().stream().map(e -> e.operation() == null ? "MODIFY" : e.operation()).distinct().toList(),
                p.provenance() == null ? null : p.provenance().ruleId(), p.reason(),
                p.risk() == null ? null : p.risk().name(), p.generatedAt(), p.proposalHash(),
                decision.map(Decision::selected).orElse(null), decision.map(Decision::decisionId).orElse(null),
                awaitingDecision(p, status, decision), mutationStatus(status), validationStatus(status));
    }

    public static List<ProposalDtos.ProposalRow> rows(RunReader run) {
        return run.proposals().stream().map(p -> row(run, p)).toList();
    }

    public static RunDtos.ChangesSummary summary(RunReader run) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byCapability = new LinkedHashMap<>();
        for (ChangeProposal p : run.proposals()) {
            byStatus.merge(String.valueOf(run.proposalStatus(p.proposalId())), 1, Integer::sum);
            byCapability.merge(p.capability().name(), 1, Integer::sum);
        }
        return new RunDtos.ChangesSummary(run.proposals().size(), byStatus, byCapability);
    }

    public static ProposalDtos.ProposalDetail detail(RunReader run, ChangeProposal p, List<ExecutionEvent> events) {
        String status = run.proposalStatus(p.proposalId());
        List<Decision> all = run.decisions();
        List<DecisionDtos.DecisionView> decisions = all.stream()
                .filter(d -> d.type() == Decision.DecisionType.PROPOSAL_APPROVAL && p.proposalId().equals(d.proposalId()))
                .map(d -> DecisionMapper.view(d, run.tamperedDecisions(), all)).toList();
        List<ProposalDtos.FileEditView> edits = new ArrayList<>();
        for (ChangeProposal.FileEdit e : p.edits()) {
            String content = e.newContent();
            boolean truncated = content != null && content.length() > MAX_CONTENT;
            int[] counts = diffCounts(e.unifiedDiff());
            String diff = SecretRedactor.redact(e.unifiedDiff());
            String shown = SecretRedactor.redact(truncated ? content.substring(0, MAX_CONTENT) : content);
            boolean redacted = diff != null && !diff.equals(e.unifiedDiff()) || shown != null && content != null
                    && !shown.equals(truncated ? content.substring(0, MAX_CONTENT) : content);
            edits.add(new ProposalDtos.FileEditView(e.fileId(), e.path(), e.newPath(), e.operation() == null ? "MODIFY"
                    : e.operation(), diff, shown, truncated, counts[0], counts[1], redacted));
        }
        ChangeProposal.Provenance prov = p.provenance();
        String baseline = run.record().machine.baselineSealHash;
        Decidability decidable = decidability(run, p, status);
        List<String> labels = p.findingRefs().stream().map(id -> run.finding(id).map(Finding::sourceFindingId).orElse(id))
                .toList();
        return new ProposalDtos.ProposalDetail(p.proposalId(), p.runId(), p.capability().name(), p.provider(),
                p.providerType().name(), p.providerVersion(), status, p.strategyOnly(), p.reason(), p.expectedOutcome(),
                p.risk() == null ? null : p.risk().name(), p.generatedAt(), p.proposalHash(), hashVerified(run, p),
                p.baselineSeal(), p.baselineSeal() == null || p.baselineSeal().equals(baseline), p.supersedes(),
                p.findingRefs(), labels, p.migrationRefs(), p.evidenceRefs(), p.knowledgeRefs(), p.affectedFileIds(),
                p.affectedSymbolIds(), p.affectedStatementIds(), p.baseHashes(), edits,
                prov == null ? null : new ProposalDtos.ProvenanceView(prov.producer(), prov.ruleId(), prov.referenceSection(),
                        prov.model(), prov.modelVersion(), prov.promptHash(), prov.contextHash(), prov.responseHash(),
                        prov.verification()),
                decisions, mutation(run, p, events), validationStatus(status),
                awaitingDecision(p, status, run.latestDecisionFor(p.proposalId())), decidable.allowed(), decidable.reason(),
                Arrays.stream(Decision.ApprovalVerdict.values()).map(Enum::name).toList());
    }

    public record Decidability(boolean allowed, String reason) {
    }

    /**
     * Whether the API accepts a decision for this proposal now. Stricter than the CLI: a proposal is
     * decided at the gate that is waiting for it, never ahead of it.
     */
    public static Decidability decidability(RunReader run, ChangeProposal p, String status) {
        RunPhase phase = run.record().machine.current;
        boolean gateB = phase == RunPhase.WAITING_FOR_REMEDIATION_APPROVAL && p.capability() != ChangeProposal.Capability.MIGRATION;
        boolean manualPatch = phase == RunPhase.NEEDS_HUMAN && p.capability() == ChangeProposal.Capability.MANUAL;
        if (!gateB && !manualPatch) {
            return new Decidability(false, "The run is at " + phase + "; " + p.capability() + " proposals are decided at "
                    + (p.capability() == ChangeProposal.Capability.MANUAL ? "Gate B or while the run NEEDS_HUMAN"
                    : p.capability() == ChangeProposal.Capability.MIGRATION ? "no per-proposal gate (the frozen plan authorizes "
                    + "deterministic migration rules)" : "Gate B (WAITING_FOR_REMEDIATION_APPROVAL)"));
        }
        if (!AWAITING.contains(status)) {
            return new Decidability(false, "The proposal is " + status + "; only PROPOSED or AWAITING_APPROVAL proposals "
                    + "can be decided");
        }
        if (run.latestDecisionFor(p.proposalId()).isPresent()) {
            return new Decidability(true, "A decision already exists; a new one must name it as superseded");
        }
        return new Decidability(true, null);
    }

    static boolean awaitingDecision(ChangeProposal p, String status, Optional<Decision> decision) {
        return p.capability() != ChangeProposal.Capability.MIGRATION && AWAITING.contains(status) && decision.isEmpty();
    }

    static String mutationStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "APPLIED", "VALIDATED", "FAILED_VALIDATION" -> "APPLIED";
            case "REVERTED" -> "ROLLED_BACK";
            case "REJECTED", "STALE", "DEFERRED" -> "NOT_APPLIED";
            case "AWAITING_APPROVAL" -> "AWAITING_APPROVAL";
            default -> "NOT_ATTEMPTED";
        };
    }

    static String validationStatus(String status) {
        return "VALIDATED".equals(status) || "FAILED_VALIDATION".equals(status) ? status : null;
    }

    private static ProposalDtos.MutationView mutation(RunReader run, ChangeProposal p, List<ExecutionEvent> events) {
        ExecutionEvent last = null;
        for (ExecutionEvent e : events) {
            if (MUTATION_EVENTS.contains(e.type()) && e.subjects().stream().anyMatch(s -> "PROPOSAL".equals(s.kind())
                    && p.proposalId().equals(s.id()))) {
                last = e;
            }
        }
        List<ProposalDtos.LineageView> lineage = run.lineage().stream().filter(e -> p.proposalId().equals(e.proposalId()))
                .map(ProposalProjector::lineage).toList();
        String status = mutationStatus(run.proposalStatus(p.proposalId()));
        if (last == null) {
            return new ProposalDtos.MutationView(status, null, null, List.of(), null, null,
                    run.record().proposalChanges.getOrDefault(p.proposalId(), List.of()), null, null, null, null, null, lineage);
        }
        Map<String, String> a = last.attributes();
        List<String> checks = a.get("checks") == null ? List.of() : List.of(a.get("checks").split(","));
        String changeIds = a.get("change_ids");
        return new ProposalDtos.MutationView(last.type() == ExecutionEventType.MUTATION_APPLIED ? "APPLIED"
                : last.type() == ExecutionEventType.MUTATION_ROLLED_BACK ? "ROLLED_BACK"
                : last.type() == ExecutionEventType.MUTATION_STARTED ? "STARTED" : status, a.get("batch"),
                a.get("authorized_by"), checks, a.get("pre_checkpoint"), a.get("checkpoint_id"),
                changeIds == null || changeIds.isBlank() ? run.record().proposalChanges.getOrDefault(p.proposalId(), List.of())
                        : List.of(changeIds.split(",")), a.get("identity_sync"),
                a.get("bypass_files") == null ? null : Integer.valueOf(a.get("bypass_files")),
                last.type() == ExecutionEventType.MUTATION_APPLIED ? null : last.message(), a.get("reason_code"),
                last.timestamp(), lineage);
    }

    private static ProposalDtos.LineageView lineage(LineageLedger.Entry e) {
        return new ProposalDtos.LineageView(e.sequence(), e.kind(), e.changeId(), e.decisionId(), e.actor(), e.fileId(),
                e.pathBefore(), e.pathAfter(), e.hashBefore(), e.hashAfter(), nz(e.symbolIds()), nz(e.statementIdsChanged()),
                nz(e.statementIdsCreated()), nz(e.statementIdsDeleted()), nz(e.validationRefs()), e.status(), e.at(),
                e.reason());
    }

    private static List<String> nz(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static boolean hashVerified(RunReader run, ChangeProposal p) {
        return run.file("proposals/" + p.proposalId() + ".sha256").map(f -> {
            try {
                return Files.readString(f, StandardCharsets.UTF_8).trim().equals(p.proposalHash());
            } catch (IOException e) {
                return false;
            }
        }).orElse(false);
    }

    static int[] diffCounts(String diff) {
        int added = 0;
        int removed = 0;
        if (diff != null) {
            for (String line : diff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    added++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    removed++;
                }
            }
        }
        return new int[]{added, removed};
    }
}
