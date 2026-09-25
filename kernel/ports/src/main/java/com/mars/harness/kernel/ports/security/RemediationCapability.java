package com.mars.harness.kernel.ports.security;

import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.ports.capability.CapabilityContext;

import java.util.List;
import java.util.Map;

/**
 * The vulnerability-remediation capability contract. It preserves the VRH chain:
 *
 * <pre>
 * finding intake -> architecture/code model -> root cause -> blast radius -> remediation strategy
 *   -> human approval -> fix -> verification -> QA/build -> audit/verdict
 * </pre>
 *
 * <p>The kernel owns the approval gate and the mutation. This contract covers what the VRH agents
 * decided: discovery, routing and planning, and verification.
 */
public interface RemediationCapability {

    String id();

    /** Read-only discovery: scanners, anchoring, root cause, blast radius. */
    SecurityDiscovery discover(CapabilityContext context, List<Finding> intake);

    /**
     * Strategy routing (catalog, then KB, then research) and planning. Plans are always
     * {@code Proposed}.
     *
     * @param researchInputs finding ID to the path of an externally produced research analysis
     *                       (the VRH 04d {@code analysis.json} judgement input); absent means none
     *                       was supplied
     * @param platformUpgradeApplied true when an approved migration has already moved the platform,
     *                       which lifts platform constraints that the migration satisfied
     */
    List<RemediationPlan> plan(CapabilityContext context, List<Finding> findings, Map<String, String> researchInputs,
                               boolean platformUpgradeApplied);

    /** VRH 05 and 06 gates plus the 07a arbiter, over an applied fix. */
    VerificationReport verify(CapabilityContext context, RemediationPlan plan, AppliedFix fix);

    /** Re-scans the given files with the same rules discovery used (security re-scan dimension). */
    List<ScanHit> rescan(CapabilityContext context, List<String> fileIds);

    record ScanHit(String ruleId, String cwe, String fileId, String path, int line, String symbolId,
                   String statementId, String snippet, String severity, String message) {
    }

    record SecurityDiscovery(List<Finding> findings, List<RootCause> rootCauses, List<BlastRadius> blastRadii,
                             List<String> evidenceRefs, List<String> gaps) {
        public SecurityDiscovery {
            findings = findings == null ? List.of() : List.copyOf(findings);
            rootCauses = rootCauses == null ? List.of() : List.copyOf(rootCauses);
            blastRadii = blastRadii == null ? List.of() : List.copyOf(blastRadii);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    record RootCause(String ref, String findingId, String statement, String location, String fileId, String symbolId,
                     String statementId, String severity, String confidence, List<String> entryPoints,
                     List<String> dataFlow, String howToFix, List<String> cweMentions, List<String> evidenceRefs) {
        public RootCause {
            entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
            dataFlow = dataFlow == null ? List.of() : List.copyOf(dataFlow);
            cweMentions = cweMentions == null ? List.of() : List.copyOf(cweMentions);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** @param scope VRH vocabulary: endpoint, service or multi-service */
    record BlastRadius(String ref, String findingId, String priority, String scope, List<String> affectedEndpoints,
                       List<String> affectedServices, List<String> affectedSymbolIds, String confidence,
                       List<String> evidenceRefs) {
        public BlastRadius {
            affectedEndpoints = affectedEndpoints == null ? List.of() : List.copyOf(affectedEndpoints);
            affectedServices = affectedServices == null ? List.of() : List.copyOf(affectedServices);
            affectedSymbolIds = affectedSymbolIds == null ? List.of() : List.copyOf(affectedSymbolIds);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /**
     * A remediation plan, always born {@code Proposed}.
     *
     * @param route         CATALOG, KB, RESEARCH, EVIDENCE_GAP or KB_GAP_REFUSED
     * @param catalogStatus VRH 04d vocabulary: match, gap or unknown
     * @param kbStatus      VRH 04d vocabulary: n/a, match, gap or unknown
     * @param proposal      the change proposal. It is strategy-only when no concrete fix exists yet
     *                      (research and KB routes without a deterministic fixer).
     */
    record RemediationPlan(String planId, String findingId, String issueId, String route, String cwe,
                           String catalogTitle, String owasp, String confidence, String plainSummary, String approach,
                           List<String> antiPatterns, List<String> alternatives, List<String> riskNotes,
                           List<String> verificationPlan, List<String> openQuestions,
                           Map<String, Object> derivedPattern, String researchStatus, String catalogStatus,
                           String kbStatus, ChangeProposal proposal, boolean blockedByPlatform,
                           Finding.PlatformRequirement platformRequirement, String legacyMarkdownRef,
                           List<String> evidenceRefs) {
        public RemediationPlan {
            antiPatterns = antiPatterns == null ? List.of() : List.copyOf(antiPatterns);
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
            riskNotes = riskNotes == null ? List.of() : List.copyOf(riskNotes);
            verificationPlan = verificationPlan == null ? List.of() : List.copyOf(verificationPlan);
            openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
            derivedPattern = derivedPattern == null ? Map.of() : Map.copyOf(derivedPattern);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    record AppliedFix(String planId, String proposalId, List<String> changeIds, List<String> changedFileIds,
                      String fixStatus, String buildLogRef) {
        public AppliedFix {
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            changedFileIds = changedFileIds == null ? List.of() : List.copyOf(changedFileIds);
        }
    }

    /**
     * VRH verdict chain for one fix. {@code decision} is Cleared or Blocked, computed by the
     * preserved arbiter rules. The kernel maps it to unified item statuses and never upgrades it.
     */
    record VerificationReport(String planId, String findingId, String fixStatus, String rescan, String rescanReason,
                              String redteam, List<String> attemptedVectors, String behavior,
                              List<String> outOfScopeChanges, String qa, String build, int score, int threshold,
                              List<String> gatesTriggered, String decision, List<String> evidenceRefs) {
        public VerificationReport {
            attemptedVectors = attemptedVectors == null ? List.of() : List.copyOf(attemptedVectors);
            outOfScopeChanges = outOfScopeChanges == null ? List.of() : List.copyOf(outOfScopeChanges);
            gatesTriggered = gatesTriggered == null ? List.of() : List.copyOf(gatesTriggered);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
