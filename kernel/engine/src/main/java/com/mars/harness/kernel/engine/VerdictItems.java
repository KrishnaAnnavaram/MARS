package com.mars.harness.kernel.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-finding and per-capability statuses (spec §22), derived from the artifacts: plans,
 * proposals, decisions and VRH verdicts. The VRH decision is carried through unchanged as
 * {@code legacyVerdict}. "Cleared" becomes FIXED. "Blocked" becomes STILL_VULNERABLE when the
 * re-scan says so, otherwise FAILED. The unified verdict never upgrades a Blocked.
 */
final class VerdictItems {

    private final RunSession session;

    VerdictItems(RunSession session, EngineConfig config) {
        this.session = session;
    }

    List<Verdict.ItemResult> items(MigrationCapability.MigrationExecution execution) {
        List<Verdict.ItemResult> items = new ArrayList<>();
        Decision.ExecutionStrategy strategy = session.record.strategy == null ? Decision.ExecutionStrategy.ANALYZE_ONLY
                : Decision.ExecutionStrategy.valueOf(session.record.strategy);
        items.add(migrationItem(strategy, execution));
        List<RemediationCapability.RemediationPlan> plans = plans();
        for (Finding finding : session.findings) {
            if (finding.status() == Finding.FindingStatus.FIXED || finding.status() == Finding.FindingStatus.CLOSED) {
                continue;
            }
            items.add(findingItem(strategy, finding, plans.stream().filter(p -> p.findingId().equals(finding.findingId()))
                    .findFirst()));
        }
        return items;
    }

    private Verdict.ItemResult migrationItem(Decision.ExecutionStrategy strategy,
                                             MigrationCapability.MigrationExecution execution) {
        MigrationAssessment assessment = assessment();
        String light = assessment == null ? "UNKNOWN" : assessment.trafficLight().name();
        if (execution != null) {
            String rounds = execution.rounds().size() + " round(s), last "
                    + (execution.rounds().isEmpty() ? "none" : execution.rounds().get(execution.rounds().size() - 1).outcome());
            return switch (execution.status()) {
                case GREEN -> new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.MIGRATED,
                        "passed; " + rounds, "Green after " + rounds, execution.evidenceRefs());
                case NEEDS_HUMAN -> new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.NEEDS_HUMAN,
                        rounds, execution.needsHumanReason(), execution.evidenceRefs());
                default -> new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.MIGRATION_INCOMPLETE,
                        rounds, "Migration did not reach a green round: " + execution.status(), execution.evidenceRefs());
            };
        }
        if (session.record.migrationPlanned) {
            return new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.INSUFFICIENT_EVIDENCE, null,
                    "Migration was authorized but could not execute: " + session.record.notes.stream()
                    .filter(n -> n.startsWith("Migration")).reduce((a, b) -> b).orElse("see plan"), List.of());
        }
        if (strategy.includesMigration() && !session.record.stopRequested) {
            return new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.NEEDS_HUMAN, null,
                    "Migration was selected but has not run", List.of());
        }
        return new Verdict.ItemResult("MIGRATION", "capability", Verdict.ItemStatus.MIGRATION_DECLINED, light,
                "Developer strategy " + strategy + " did not include migration (assessment " + light + ")", List.of());
    }

    private Verdict.ItemResult findingItem(Decision.ExecutionStrategy strategy, Finding finding,
                                           Optional<RemediationCapability.RemediationPlan> plan) {
        String id = finding.findingId();
        String label = finding.sourceFindingId() + " " + String.join(",", finding.cwe());
        if (!session.record.securityExecuted && !strategy.includesSecurity()) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.DEFERRED_BY_DEVELOPER, null,
                    label + ": strategy " + strategy + " did not include security remediation", finding.evidenceRefs());
        }
        if (plan.isEmpty()) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.NEEDS_HUMAN, null,
                    label + ": no remediation plan was produced", finding.evidenceRefs());
        }
        RemediationCapability.RemediationPlan p = plan.get();
        List<ChangeProposal> proposals = session.proposals.all().stream()
                .filter(x -> x.findingRefs().contains(id) && x.capability() != ChangeProposal.Capability.MIGRATION).toList();
        String legacy = session.record.verification.get(p.planId());
        // the most advanced proposal for the finding decides its status
        for (ChangeProposal proposal : proposals.reversed()) {
            String status = session.record.proposalStatus.get(proposal.proposalId());
            if ("APPLIED".equals(status) || "VALIDATED".equals(status) || "FAILED_VALIDATION".equals(status)) {
                if (legacy == null) {
                    return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.NOT_COMPARED, null,
                            label + ": fix applied but not verified", finding.evidenceRefs());
                }
                RemediationCapability.VerificationReport report = report(p.planId());
                if ("Cleared".equals(legacy)) {
                    return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.FIXED, legacyText(report),
                            label + ": " + legacyText(report), report == null ? List.of() : report.evidenceRefs());
                }
                boolean stillVulnerable = report != null && "STILL_VULNERABLE".equals(report.rescan());
                return new Verdict.ItemResult(id, "finding", stillVulnerable ? Verdict.ItemStatus.STILL_VULNERABLE
                        : Verdict.ItemStatus.FAILED, legacyText(report), label + ": " + legacyText(report),
                        report == null ? List.of() : report.evidenceRefs());
            }
        }
        if (p.blockedByPlatform() && !session.record.migrationExecuted) {
            Finding.PlatformRequirement req = p.platformRequirement();
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.BLOCKED_BY_PLATFORM, null,
                    label + ": remediation requires " + (req == null ? "a newer platform" : req.requiresPlatform() + " "
                            + req.requiresPlatformMinimum() + "+ (" + req.component() + " >= " + req.minimumFixedVersion() + ")")
                            + " and migration was not executed", req == null ? List.of() : req.evidenceRefs());
        }
        for (ChangeProposal proposal : proposals.reversed()) {
            String status = session.record.proposalStatus.get(proposal.proposalId());
            Optional<Decision> decision = session.approvals.latestForProposal(proposal.proposalId());
            if ("REJECTED".equals(status) || decision.map(d -> "REJECTED".equals(d.selected())).orElse(false)) {
                return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.REJECTED_BY_DEVELOPER, null,
                        label + ": " + decision.map(d -> d.actor() + " rejected: " + d.rationale()).orElse("rejected"),
                        finding.evidenceRefs());
            }
            if ("DEFERRED".equals(status) || decision.map(d -> "DEFERRED".equals(d.selected())).orElse(false)) {
                return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.DEFERRED_BY_DEVELOPER, null,
                        label + ": deferred by " + decision.map(Decision::actor).orElse("developer"), finding.evidenceRefs());
            }
            if ("STALE".equals(status)) {
                return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.NEEDS_HUMAN, null,
                        label + ": proposal became stale; re-plan", finding.evidenceRefs());
            }
        }
        if ("ALREADY_REMEDIATED".equals(p.route())) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.FIXED, "re-scan: outside affected range",
                    label + ": remediated by an earlier authorized change (re-scan confirms the resolved version is fixed)",
                    p.evidenceRefs());
        }
        if ("UNCLASSIFIED".equals(p.route())) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.NEEDS_HUMAN, null,
                    label + ": no CWE could be established; a human must classify it before routing", p.evidenceRefs());
        }
        if ("EVIDENCE_GAP".equals(p.route())) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.INSUFFICIENT_EVIDENCE, "evidence gap",
                    label + ": research could not establish a confident remediation (evidence-gap plan)", p.evidenceRefs());
        }
        if ("KB_GAP_REFUSED".equals(p.route())) {
            return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.NEEDS_HUMAN, null,
                    label + ": catalog and KB gap with no research input; no strategy was invented", p.evidenceRefs());
        }
        if (p.proposal() != null && p.proposal().strategyOnly()) {
            boolean approved = session.approvals.latestForProposal(p.proposal().proposalId()).map(Decision::approved)
                    .orElse(false);
            return new Verdict.ItemResult(id, "finding", approved ? Verdict.ItemStatus.NEEDS_HUMAN
                    : Verdict.ItemStatus.PENDING_APPROVAL, null, label + (approved
                    ? ": strategy approved; a concrete fix proposal must be submitted and approved"
                    : ": " + p.route() + " strategy awaits human approval"), p.evidenceRefs());
        }
        return new Verdict.ItemResult(id, "finding", Verdict.ItemStatus.PENDING_APPROVAL, null,
                label + ": remediation proposal awaits a human decision (a missing decision is not approval)", p.evidenceRefs());
    }

    private static String legacyText(RemediationCapability.VerificationReport report) {
        if (report == null) {
            return null;
        }
        return report.decision() + " (" + report.score() + "/" + report.threshold()
                + (report.gatesTriggered().isEmpty() ? "" : "; gates " + report.gatesTriggered()) + "; rescan " + report.rescan()
                + ", redteam " + report.redteam() + ", behavior " + report.behavior() + ", qa " + report.qa() + ", build "
                + report.build() + ")";
    }

    private RemediationCapability.VerificationReport report(String planId) {
        Path file = session.layout.area("validation/security").resolve(planId + ".json");
        return Files.isRegularFile(file) ? KernelJson.read(file, RemediationCapability.VerificationReport.class) : null;
    }

    private List<RemediationCapability.RemediationPlan> plans() {
        Path file = session.layout.area("plans").resolve("remediation-plans.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        return KernelJson.mapper().convertValue(KernelJson.read(file),
                new TypeReference<List<RemediationCapability.RemediationPlan>>() { });
    }

    private MigrationAssessment assessment() {
        Path file = session.layout.area("discovery").resolve("migration").resolve("migration-assessment.json");
        return Files.isRegularFile(file) ? KernelJson.read(file, MigrationAssessment.class) : null;
    }
}
