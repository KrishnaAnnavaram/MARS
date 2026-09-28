package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.controlcenter.api.dto.FindingDtos;
import com.mars.harness.controlcenter.api.dto.ProposalDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.identity.Location;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The security cockpit: findings, their analysis, plans, proposals, decisions, verification and outcome. */
public final class FindingProjector {

    private static final int CONTEXT_LINES = 6;

    private FindingProjector() {
    }

    public static FindingDtos.SecurityView view(RunReader run) {
        List<FindingDtos.FindingRow> rows = run.findings().stream().map(f -> row(run, f)).toList();
        List<String> gaps = run.securityDiscovery().map(RemediationCapability.SecurityDiscovery::gaps).orElse(List.of());
        List<String> sources = run.findings().stream().map(Finding::source).distinct().toList();
        return new FindingDtos.SecurityView(summary(run), rows, gaps, sources, run.securityDiscovery().isPresent());
    }

    public static RunDtos.SecuritySummary summary(RunReader run) {
        Map<String, Integer> severity = new LinkedHashMap<>();
        for (Finding.Severity s : Finding.Severity.values()) {
            severity.put(s.name(), 0);
        }
        run.findings().forEach(f -> severity.merge(f.severity().name(), 1, Integer::sum));
        severity.values().removeIf(v -> v == 0);
        int analysed = (int) run.findings().stream().filter(f -> f.rootCauseRef() != null && f.blastRadiusRef() != null).count();
        List<ChangeProposal> security = run.proposals().stream()
                .filter(p -> p.capability() != ChangeProposal.Capability.MIGRATION).toList();
        int awaiting = (int) security.stream().filter(p -> ProposalProjector.awaitingDecision(p,
                run.proposalStatus(p.proposalId()), run.latestDecisionFor(p.proposalId()))).count();
        int applied = (int) security.stream().filter(p -> "APPLIED".equals(ProposalProjector.mutationStatus(
                run.proposalStatus(p.proposalId())))).count();
        int cleared = (int) run.record().verification.values().stream().filter("Cleared"::equals).count();
        int blocked = (int) run.record().verification.values().stream().filter(v -> !"Cleared".equals(v)).count();
        int deferred = 0;
        int rejected = 0;
        for (ChangeProposal p : security) {
            Optional<Decision> d = run.latestDecisionFor(p.proposalId());
            if (d.isPresent() && "DEFERRED".equals(d.get().selected())) {
                deferred++;
            } else if (d.isPresent() && "REJECTED".equals(d.get().selected())) {
                rejected++;
            }
        }
        int platform = (int) run.remediationPlans().stream()
                .filter(p -> p.blockedByPlatform() && !run.record().migrationExecuted).count();
        return new RunDtos.SecuritySummary(run.findings().size(), severity, analysed, run.remediationPlans().size(), awaiting,
                applied, cleared, blocked, deferred, rejected, platform);
    }

    public static FindingDtos.FindingRow row(RunReader run, Finding f) {
        Optional<RemediationCapability.RemediationPlan> plan = plan(run, f);
        Optional<ChangeProposal> proposal = proposal(run, f, plan);
        String proposalStatus = proposal.map(p -> run.proposalStatus(p.proposalId())).orElse(null);
        String decision = proposal.flatMap(p -> run.latestDecisionFor(p.proposalId())).map(Decision::selected).orElse(null);
        String verification = plan.map(p -> run.record().verification.get(p.planId())).orElse(null);
        Optional<RemediationCapability.BlastRadius> radius = run.securityDiscovery().flatMap(d -> d.blastRadii().stream()
                .filter(b -> f.findingId().equals(b.findingId())).findFirst());
        return new FindingDtos.FindingRow(f.findingId(), f.sourceFindingId(), f.source(), f.severity().name(), f.cwe(),
                f.title(), location(f.location()), f.anchorQuality(), plan.map(RemediationCapability.RemediationPlan::route)
                .orElse(null), plan.map(RemediationCapability.RemediationPlan::planId).orElse(null),
                proposal.map(ChangeProposal::proposalId).orElse(null), proposalStatus, decision, verification,
                item(run, f.findingId()).map(i -> i.status().name()).orElse(null),
                plan.map(RemediationCapability.RemediationPlan::blockedByPlatform).orElse(false),
                radius.map(RemediationCapability.BlastRadius::scope).orElse(null));
    }

    public static FindingDtos.FindingDetail detail(RunReader run, Finding f) {
        Optional<RemediationCapability.RemediationPlan> plan = plan(run, f);
        Optional<ChangeProposal> proposal = proposal(run, f, plan);
        Optional<RemediationCapability.RootCause> rca = run.securityDiscovery().flatMap(d -> d.rootCauses().stream()
                .filter(r -> f.findingId().equals(r.findingId())).findFirst());
        Optional<RemediationCapability.BlastRadius> radius = run.securityDiscovery().flatMap(d -> d.blastRadii().stream()
                .filter(b -> f.findingId().equals(b.findingId())).findFirst());
        Optional<RemediationCapability.VerificationReport> verification = plan.flatMap(p -> run.verification(p.planId()));
        List<Decision> all = run.decisions();
        List<DecisionDtos.DecisionView> decisions = proposal.map(p -> all.stream()
                .filter(d -> p.proposalId().equals(d.proposalId()))
                .map(d -> DecisionMapper.view(d, run.tamperedDecisions(), all)).toList()).orElse(List.of());
        Optional<Verdict.ItemResult> item = item(run, f.findingId());
        Finding.PlatformRequirement req = f.platformRequirement();
        Map<String, String> resolution = new LinkedHashMap<>();
        if (f.resolution() != null) {
            resolution.put("status", f.resolution().status());
            resolution.put("reason", f.resolution().reason());
            resolution.put("decided_by", f.resolution().decidedBy());
        }
        return new FindingDtos.FindingDetail(f.findingId(), f.sourceFindingId(), f.source(), f.ruleId(), f.cwe(), f.cve(),
                f.severity().name(), f.status().name(), f.title(), f.description(), location(f.location()),
                f.codeFlow().stream().map(FindingProjector::location).toList(), f.fileId(),
                f.location() == null ? null : f.location().path(), f.programUnitId(), f.symbolId(), f.statementId(),
                f.anchorQuality(), f.fingerprint(), req == null ? null : new FindingDtos.PlatformRequirementView(req.component(),
                req.currentVersion(), req.minimumFixedVersion(), req.requiresPlatform(), req.requiresPlatformMinimum(),
                req.requiresJavaMinimum(), req.basis(), req.evidenceRefs()), f.evidenceRefs(),
                rca.map(FindingProjector::rootCause).orElse(null), radius.map(FindingProjector::blastRadius).orElse(null),
                plan.map(p -> plan(p)).orElse(null), proposal.map(p -> ProposalProjector.row(run, p)).orElse(null), decisions,
                verification.map(FindingProjector::verification).orElse(null), item.map(i -> i.status().name()).orElse(null),
                item.map(Verdict.ItemResult::reason).orElse(null),
                journey(run, f, plan, proposal, rca.isPresent(), radius.isPresent(), verification, item),
                sourceContext(run, f.location()), resolution);
    }

    // ------------------------------------------------------------------ the remediation journey

    private static List<FindingDtos.JourneyStep> journey(RunReader run, Finding f,
                                                         Optional<RemediationCapability.RemediationPlan> plan,
                                                         Optional<ChangeProposal> proposal, boolean hasRca, boolean hasRadius,
                                                         Optional<RemediationCapability.VerificationReport> verification,
                                                         Optional<Verdict.ItemResult> item) {
        RunPhase phase = run.record().machine.current;
        boolean securityInPath = run.record().strategy != null
                && com.mars.harness.kernel.core.decision.Decision.ExecutionStrategy.valueOf(run.record().strategy)
                .includesSecurity();
        boolean decidedAtGateA = run.record().strategy != null;
        List<FindingDtos.JourneyStep> steps = new ArrayList<>();
        steps.add(new FindingDtos.JourneyStep("INTAKE", "Intake", "DONE", f.source() + " " + f.sourceFindingId()));
        steps.add(new FindingDtos.JourneyStep("ANCHORING", "Identity anchoring", "DONE", "anchor " + f.anchorQuality()
                + (f.statementId() != null ? " (statement " + f.statementId() + ")" : f.symbolId() != null ? " (symbol "
                + f.symbolId() + ")" : f.fileId() != null ? " (file " + f.fileId() + ")" : "")));
        steps.add(new FindingDtos.JourneyStep("RCA", "Root cause", hasRca ? "DONE" : "PENDING", hasRca ? f.rootCauseRef() : null));
        steps.add(new FindingDtos.JourneyStep("BLAST_RADIUS", "Blast radius", hasRadius ? "DONE" : "PENDING",
                hasRadius ? f.blastRadiusRef() : null));
        String notInPath = decidedAtGateA && !securityInPath ? "SKIPPED" : "PENDING";
        String route = plan.map(p -> p.route() + (p.cwe() == null ? "" : " " + p.cwe()) + " (catalog " + p.catalogStatus()
                + ", KB " + p.kbStatus() + ")").orElse(null);
        steps.add(new FindingDtos.JourneyStep("ROUTING", "Routing (catalog → KB → research)", plan.isPresent() ? "DONE"
                : notInPath, route));
        String proposalStatus = proposal.map(p -> run.proposalStatus(p.proposalId())).orElse(null);
        if (plan.isPresent() && plan.get().blockedByPlatform() && !run.record().migrationExecuted) {
            steps.add(new FindingDtos.JourneyStep("PROPOSAL", "Fix proposal", "SKIPPED", "blocked by platform: requires "
                    + (plan.get().platformRequirement() == null ? "a newer platform" : plan.get().platformRequirement()
                    .requiresPlatform() + " " + plan.get().platformRequirement().requiresPlatformMinimum() + "+")));
        } else {
            steps.add(new FindingDtos.JourneyStep("PROPOSAL", "Fix proposal", proposal.isPresent() ? "DONE"
                    : plan.isPresent() ? "SKIPPED" : notInPath, proposal.map(p -> p.proposalId() + (p.strategyOnly()
                    ? " (strategy only: authorizes producing a concrete fix, applies nothing)" : " (concrete fix)"))
                    .orElse(plan.isPresent() ? "no proposal for route " + plan.get().route() : null)));
        }
        Optional<Decision> decision = proposal.flatMap(p -> run.latestDecisionFor(p.proposalId()));
        String approval;
        if (decision.isPresent()) {
            approval = "DONE";
        } else if (proposal.isPresent() && phase == RunPhase.WAITING_FOR_REMEDIATION_APPROVAL
                && Set.of("PROPOSED", "AWAITING_APPROVAL").contains(proposalStatus)) {
            approval = "WAITING";
        } else {
            approval = proposal.isPresent() ? (phase.terminal() ? "SKIPPED" : "PENDING") : "NOT_APPLICABLE";
        }
        steps.add(new FindingDtos.JourneyStep("APPROVAL", "Human approval (Gate B)", approval, decision.map(d -> d.selected()
                + " by " + d.actor() + " (" + d.decisionId() + ")").orElse(null)));
        String mutation = ProposalProjector.mutationStatus(proposalStatus);
        String mutationStep = "APPLIED".equals(mutation) ? "DONE" : decision.map(Decision::approved).orElse(false)
                && proposal.map(ChangeProposal::strategyOnly).orElse(false) ? "NOT_APPLICABLE"
                : decision.map(Decision::approved).orElse(false) && Set.of("REJECTED", "STALE", "REVERTED")
                .contains(String.valueOf(proposalStatus)) ? "FAILED" : decision.isPresent() && !decision.get().approved()
                ? "SKIPPED" : proposal.isPresent() ? (phase.terminal() ? "SKIPPED" : "PENDING") : "NOT_APPLICABLE";
        steps.add(new FindingDtos.JourneyStep("MUTATION", "Mutation Gateway", mutationStep, proposalStatus));
        steps.add(new FindingDtos.JourneyStep("VERIFICATION", "Verification (re-scan, red-team, behaviour, QA, build)",
                verification.isPresent() ? "DONE" : "APPLIED".equals(mutation) ? "PENDING" : "NOT_APPLICABLE",
                verification.map(v -> "rescan " + v.rescan() + ", red-team " + v.redteam() + ", behaviour " + v.behavior()
                        + ", QA " + v.qa() + ", build " + v.build()).orElse(null)));
        steps.add(new FindingDtos.JourneyStep("ARBITER", "Merge arbiter", verification.map(v -> "Cleared".equals(v.decision())
                ? "DONE" : "FAILED").orElse("APPLIED".equals(mutation) ? "PENDING" : "NOT_APPLICABLE"),
                verification.map(v -> v.decision() + " (score " + v.score() + "/" + v.threshold() + ")").orElse(null)));
        steps.add(new FindingDtos.JourneyStep("OUTCOME", "Outcome", item.isPresent() ? "DONE" : "PENDING",
                item.map(i -> i.status().name() + (i.reason() == null ? "" : ": " + i.reason())).orElse(null)));
        // the first open step of a live run is where the finding is now
        if (!phase.terminal()) {
            for (int i = 0; i < steps.size(); i++) {
                FindingDtos.JourneyStep s = steps.get(i);
                if (s.status().equals("PENDING")) {
                    steps.set(i, new FindingDtos.JourneyStep(s.id(), s.label(), "CURRENT", s.detail()));
                    break;
                }
                if (s.status().equals("WAITING")) {
                    break;
                }
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------ helpers

    static Optional<RemediationCapability.RemediationPlan> plan(RunReader run, Finding f) {
        List<RemediationCapability.RemediationPlan> plans = run.remediationPlans().stream()
                .filter(p -> f.findingId().equals(p.findingId())).toList();
        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.get(plans.size() - 1));
    }

    static Optional<ChangeProposal> proposal(RunReader run, Finding f, Optional<RemediationCapability.RemediationPlan> plan) {
        if (plan.isPresent() && plan.get().proposal() != null) {
            Optional<ChangeProposal> registered = run.proposal(plan.get().proposal().proposalId());
            if (registered.isPresent()) {
                // a later concrete (e.g. manual) proposal for the same finding supersedes a strategy-only one in review
                if (registered.get().strategyOnly()) {
                    Optional<ChangeProposal> concrete = run.proposals().stream().filter(p -> !p.strategyOnly()
                            && p.findingRefs().contains(f.findingId())).reduce((a, b) -> b);
                    if (concrete.isPresent()) {
                        return concrete;
                    }
                }
                return registered;
            }
        }
        return run.proposals().stream().filter(p -> p.findingRefs().contains(f.findingId())).reduce((a, b) -> b);
    }

    static Optional<Verdict.ItemResult> item(RunReader run, String itemId) {
        return run.verdict().flatMap(v -> v.items().stream().filter(i -> itemId.equals(i.itemId())).findFirst());
    }

    static FindingDtos.LocationView location(Location l) {
        return l == null ? null : new FindingDtos.LocationView(l.path(), l.lineStart(), l.lineEnd());
    }

    private static FindingDtos.RootCauseView rootCause(RemediationCapability.RootCause r) {
        return new FindingDtos.RootCauseView(r.ref(), r.statement(), r.location(), r.fileId(), r.symbolId(), r.statementId(),
                r.severity(), r.confidence(), r.entryPoints(), r.dataFlow(), r.howToFix(), r.evidenceRefs());
    }

    private static FindingDtos.BlastRadiusView blastRadius(RemediationCapability.BlastRadius b) {
        return new FindingDtos.BlastRadiusView(b.ref(), b.priority(), b.scope(), b.affectedEndpoints(), b.affectedServices(),
                b.affectedSymbolIds(), b.confidence(), b.evidenceRefs());
    }

    static FindingDtos.PlanView plan(RemediationCapability.RemediationPlan p) {
        return new FindingDtos.PlanView(p.planId(), p.issueId(), p.route(), p.cwe(), p.catalogTitle(), p.owasp(),
                p.confidence(), p.plainSummary(), p.approach(), p.antiPatterns(), p.alternatives(), p.riskNotes(),
                p.verificationPlan(), p.openQuestions(), p.researchStatus(), p.catalogStatus(), p.kbStatus(),
                p.blockedByPlatform(), p.proposal() == null ? null : p.proposal().proposalId(),
                p.proposal() != null && p.proposal().strategyOnly(), p.evidenceRefs());
    }

    static FindingDtos.VerificationView verification(RemediationCapability.VerificationReport v) {
        return new FindingDtos.VerificationView(v.planId(), v.fixStatus(), v.rescan(), v.rescanReason(), v.redteam(),
                v.attemptedVectors(), v.behavior(), v.outOfScopeChanges(), v.qa(), v.build(), v.score(), v.threshold(),
                v.gatesTriggered(), v.decision(), v.evidenceRefs());
    }

    /** Lines around the anchor from the run's workspace copy, or its immutable snapshot; credentials masked. */
    static FindingDtos.SourceContext sourceContext(RunReader run, Location location) {
        if (location == null || location.path() == null || location.lineStart() <= 0) {
            return null;
        }
        for (String origin : List.of("migration", "original")) {
            Optional<Path> file = run.file(origin + "/" + location.path());
            if (file.isEmpty()) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file.get(), StandardCharsets.UTF_8);
                int start = Math.max(1, location.lineStart() - CONTEXT_LINES);
                int end = Math.min(lines.size(), Math.max(location.lineEnd(), location.lineStart()) + CONTEXT_LINES);
                if (start > lines.size()) {
                    return null;
                }
                List<String> shown = lines.subList(start - 1, end).stream().map(SecretRedactor::redact).toList();
                return new FindingDtos.SourceContext(location.path(), start, shown, location.lineStart(),
                        Math.max(location.lineEnd(), location.lineStart()), origin.equals("migration")
                        ? "run workspace (current)" : "immutable source snapshot");
            } catch (IOException | RuntimeException e) {
                return null; // binary or unreadable: no context rather than a wrong one
            }
        }
        return null;
    }
}
