package com.mars.harness.kernel.engine.discovery;

import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.migration.CombinedAssessment;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.migration.SequenceRecommendation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evidence-backed execution sequence advice (spec §24) and the Human Gate A combined
 * assessment (spec §10).
 *
 * <p>It never forces {@code MIGRATE_FIRST}. The rules, in order:
 *
 * <ol>
 *   <li>A platform-constrained finding while migration evidence is UNKNOWN:
 *       INSUFFICIENT_EVIDENCE. Unknown is not coerced into a sequence.</li>
 *   <li>An urgent (critical or high) finding whose remediation requires a platform line the
 *       project is not on: MIGRATE_FIRST.</li>
 *   <li>Urgent findings that are independently remediable while migration is recommended or
 *       required only for other reasons: SECURITY_FIRST.</li>
 *   <li>A non-urgent platform-constrained finding with a RED light: MIGRATE_FIRST.</li>
 *   <li>Otherwise NO_SEQUENCE_REQUIRED.</li>
 * </ol>
 *
 * <p>The developer's decision is authoritative whatever this returns.
 */
public final class SequenceAdvisor {

    private SequenceAdvisor() {
    }

    public static SequenceRecommendation advise(MigrationAssessment assessment, List<Finding> findings) {
        List<Finding> open = findings.stream().filter(SequenceAdvisor::open).toList();
        List<Finding> constrained = open.stream().filter(f -> f.platformRequirement() != null).toList();
        List<Finding> urgentConstrained = constrained.stream().filter(f -> f.severity().urgent()).toList();
        List<Finding> urgentIndependent = open.stream()
                .filter(f -> f.severity().urgent() && f.platformRequirement() == null).toList();
        MigrationAssessment.TrafficLight light = assessment.trafficLight();
        List<String> evidence = new ArrayList<>(assessment.evidence());

        if (light == MigrationAssessment.TrafficLight.UNKNOWN && !constrained.isEmpty()) {
            return new SequenceRecommendation(SequenceRecommendation.Sequence.INSUFFICIENT_EVIDENCE,
                    constrained.size() + " finding(s) depend on a platform change, but the migration evidence is "
                            + "insufficient to classify the platform; no sequence can be grounded",
                    ids(constrained), evidence);
        }
        if (!urgentConstrained.isEmpty()) {
            Finding driver = urgentConstrained.get(0);
            constrained.forEach(f -> evidence.addAll(f.platformRequirement().evidenceRefs()));
            return new SequenceRecommendation(SequenceRecommendation.Sequence.MIGRATE_FIRST,
                    "Urgent finding " + driver.sourceFindingId() + " (" + driver.severity() + ") can only be remediated on "
                            + driver.platformRequirement().requiresPlatform() + " "
                            + driver.platformRequirement().requiresPlatformMinimum() + "+ ("
                            + driver.platformRequirement().component() + " fixed in "
                            + driver.platformRequirement().minimumFixedVersion() + "): migrating first unblocks it",
                    ids(urgentConstrained), evidence);
        }
        if (!urgentIndependent.isEmpty() && (light == MigrationAssessment.TrafficLight.YELLOW
                || light == MigrationAssessment.TrafficLight.RED || light == MigrationAssessment.TrafficLight.UNKNOWN)) {
            return new SequenceRecommendation(SequenceRecommendation.Sequence.SECURITY_FIRST,
                    urgentIndependent.size() + " urgent finding(s) are remediable on the current platform; migration is "
                            + light + " with complexity " + assessment.complexity() + " (effort "
                            + assessment.effort().migrationEffortScore() + "/100), so securing first shortens exposure",
                    ids(urgentIndependent), evidence);
        }
        if (!constrained.isEmpty() && light == MigrationAssessment.TrafficLight.RED) {
            constrained.forEach(f -> evidence.addAll(f.platformRequirement().evidenceRefs()));
            return new SequenceRecommendation(SequenceRecommendation.Sequence.MIGRATE_FIRST,
                    constrained.size() + " finding(s) require the target platform; migration is a prerequisite for them",
                    ids(constrained), evidence);
        }
        return new SequenceRecommendation(SequenceRecommendation.Sequence.NO_SEQUENCE_REQUIRED,
                "No finding depends on the migration and no ordering changes exposure", List.of(), evidence);
    }

    public static CombinedAssessment combine(String runId, MigrationAssessment assessment, List<Finding> findings,
                                             SequenceRecommendation sequence) {
        List<Finding> open = findings.stream().filter(SequenceAdvisor::open).toList();
        CombinedAssessment.SecuritySummary security = new CombinedAssessment.SecuritySummary(open.size(),
                count(open, Finding.Severity.CRITICAL), count(open, Finding.Severity.HIGH),
                count(open, Finding.Severity.MEDIUM), count(open, Finding.Severity.LOW),
                (int) open.stream().filter(f -> f.severity() == Finding.Severity.UNKNOWN || f.severity() == Finding.Severity.INFO).count(),
                (int) open.stream().filter(f -> f.platformRequirement() == null).count(),
                (int) open.stream().filter(f -> f.platformRequirement() != null).count(),
                (int) open.stream().filter(f -> "UNANCHORED".equals(f.anchorQuality())).count(),
                open.stream().map(Finding::source).distinct().toList());
        List<CombinedAssessment.Interaction> interactions = new ArrayList<>();
        for (Finding f : open) {
            if (f.platformRequirement() != null) {
                interactions.add(new CombinedAssessment.Interaction("REMEDIATION_REQUIRES_PLATFORM", f.findingId(),
                        f.sourceFindingId() + ": fix requires " + f.platformRequirement().requiresPlatform() + " "
                                + f.platformRequirement().requiresPlatformMinimum() + "+ ("
                                + f.platformRequirement().basis() + ")", f.platformRequirement().evidenceRefs()));
            }
        }
        for (MigrationAssessment.ObjectiveImpact objective : assessment.objectives()) {
            if (objective.requiresMigration()) {
                interactions.add(new CombinedAssessment.Interaction("MIGRATION_PREREQUISITE", objective.objectiveId(),
                        objective.why(), objective.evidenceRefs()));
            }
        }
        String recommended = switch (sequence.sequence()) {
            case MIGRATE_FIRST -> "MIGRATE_FIRST";
            case SECURITY_FIRST -> "SECURITY_FIRST";
            case INSUFFICIENT_EVIDENCE -> "ANALYZE_ONLY";
            case NO_SEQUENCE_REQUIRED -> {
                boolean migrate = assessment.trafficLight() == MigrationAssessment.TrafficLight.YELLOW
                        || assessment.trafficLight() == MigrationAssessment.TrafficLight.RED;
                if (!open.isEmpty() && migrate) {
                    yield "SECURITY_FIRST";
                }
                if (!open.isEmpty()) {
                    yield "SECURITY_ONLY";
                }
                yield migrate ? "MIGRATION_ONLY" : "ANALYZE_ONLY";
            }
        };
        List<String> unknowns = assessment.unknowns().stream().map(u -> u.dimension() + ": " + u.question()).toList();
        return new CombinedAssessment(runId, Instant.now().toString(), assessment.assessmentId(),
                assessment.trafficLight(), assessment.complexity(), assessment.effort().migrationEffortScore(),
                assessment.evidenceConfidence(), security, interactions, sequence,
                List.of("MIGRATE_FIRST", "SECURITY_FIRST", "MIGRATION_ONLY", "SECURITY_ONLY", "ANALYZE_ONLY", "STOP"),
                recommended, unknowns);
    }

    static boolean open(Finding f) {
        return f.status() != Finding.FindingStatus.FIXED && f.status() != Finding.FindingStatus.CLOSED;
    }

    private static int count(List<Finding> findings, Finding.Severity severity) {
        return (int) findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static List<String> ids(List<Finding> findings) {
        return findings.stream().map(Finding::findingId).toList();
    }
}
