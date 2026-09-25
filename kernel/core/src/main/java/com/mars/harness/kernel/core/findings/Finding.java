package com.mars.harness.kernel.core.findings;

import com.mars.harness.kernel.core.identity.Location;

import java.util.List;

/**
 * Canonical security finding (spec §14). Every intake (Excel register, SARIF, rule scanner,
 * dependency advisories) normalises into this shape. None of the sources is the system of
 * record; the run's {@code findings/findings.json} is.
 *
 * <p>Identity-aware: the finding is anchored to FILE_ID, PROGRAM_UNIT_ID, SYMBOL_ID and
 * STATEMENT_ID, not only to a path and line. It therefore survives line movement, file rename and
 * migration.
 *
 * @param fingerprint stable identity-aware fingerprint (see {@link FindingFingerprint})
 * @param platformRequirement when a remediation is only available on a newer platform line, the
 *                            requirement and its evidence; null when none is known
 */
public record Finding(String findingId, String source, String sourceFindingId, String ruleId,
                      List<String> cwe, List<String> cve, Severity severity, FindingStatus status,
                      String title, String description, String fileId, String programUnitId, String symbolId,
                      String statementId, Location location, List<Location> codeFlow, String fingerprint,
                      String rootCauseRef, String blastRadiusRef, List<String> evidenceRefs,
                      String firstSeenRun, String lastSeenRun, Resolution resolution,
                      PlatformRequirement platformRequirement, List<String> affectedSymbols,
                      List<String> affectedFiles, String anchorQuality) {

    public Finding {
        cwe = cwe == null ? List.of() : List.copyOf(cwe);
        cve = cve == null ? List.of() : List.copyOf(cve);
        codeFlow = codeFlow == null ? List.of() : List.copyOf(codeFlow);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        affectedSymbols = affectedSymbols == null ? List.of() : List.copyOf(affectedSymbols);
        affectedFiles = affectedFiles == null ? List.of() : List.copyOf(affectedFiles);
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO, UNKNOWN;

        public static Severity parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (v) {
                case "CRITICAL", "BLOCKER" -> CRITICAL;
                case "HIGH", "ERROR", "MAJOR" -> HIGH;
                case "MEDIUM", "MODERATE", "WARNING" -> MEDIUM;
                case "LOW", "MINOR", "NOTE" -> LOW;
                case "INFO", "INFORMATIONAL", "NONE" -> INFO;
                default -> UNKNOWN;
            };
        }

        /** VRH register vocabulary (Critical|High|Medium|Low) used by the merge arbiter thresholds. */
        public String registerLabel() {
            return switch (this) {
                case CRITICAL -> "Critical";
                case HIGH -> "High";
                case MEDIUM -> "Medium";
                case LOW -> "Low";
                default -> null;
            };
        }

        public boolean urgent() {
            return this == CRITICAL || this == HIGH;
        }
    }

    public enum FindingStatus { OPEN, IN_PROGRESS, FIXED, CLOSED, UNKNOWN }

    /** The per-finding outcome. Final values are the spec §22 item statuses. */
    public record Resolution(String status, String reason, List<String> changeIds, List<String> evidenceRefs,
                             String decidedBy) {
        public Resolution {
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /**
     * A remediation that requires a platform line the project is not on. It is grounded: it names
     * the component, the minimum fixed version, the platform constraint and the evidence.
     */
    public record PlatformRequirement(String component, String currentVersion, String minimumFixedVersion,
                                      String requiresPlatform, String requiresPlatformMinimum,
                                      String requiresJavaMinimum, String basis, List<String> evidenceRefs) {
        public PlatformRequirement {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public Finding withResolution(Resolution newResolution) {
        return new Finding(findingId, source, sourceFindingId, ruleId, cwe, cve, severity, status, title, description,
                fileId, programUnitId, symbolId, statementId, location, codeFlow, fingerprint, rootCauseRef,
                blastRadiusRef, evidenceRefs, firstSeenRun, lastSeenRun, newResolution, platformRequirement,
                affectedSymbols, affectedFiles, anchorQuality);
    }

    public Finding withAnalysisRefs(String rootCause, String blastRadius) {
        return new Finding(findingId, source, sourceFindingId, ruleId, cwe, cve, severity, status, title, description,
                fileId, programUnitId, symbolId, statementId, location, codeFlow, fingerprint, rootCause,
                blastRadius, evidenceRefs, firstSeenRun, lastSeenRun, resolution, platformRequirement,
                affectedSymbols, affectedFiles, anchorQuality);
    }

    public Finding withEvidence(List<String> refs) {
        List<String> all = new java.util.ArrayList<>(evidenceRefs);
        refs.stream().filter(r -> !all.contains(r)).forEach(all::add);
        return new Finding(findingId, source, sourceFindingId, ruleId, cwe, cve, severity, status, title, description,
                fileId, programUnitId, symbolId, statementId, location, codeFlow, fingerprint, rootCauseRef,
                blastRadiusRef, all, firstSeenRun, lastSeenRun, resolution, platformRequirement,
                affectedSymbols, affectedFiles, anchorQuality);
    }

    public Finding withRuleId(String newRuleId) {
        return new Finding(findingId, source, sourceFindingId, newRuleId, cwe, cve, severity, status, title, description,
                fileId, programUnitId, symbolId, statementId, location, codeFlow, fingerprint, rootCauseRef, blastRadiusRef,
                evidenceRefs, firstSeenRun, lastSeenRun, resolution, platformRequirement, affectedSymbols, affectedFiles,
                anchorQuality);
    }

    public Finding withAnchor(String newFileId, String unitId, String symId, String stmtId, Location loc,
                              String quality, String newFingerprint) {
        return new Finding(findingId, source, sourceFindingId, ruleId, cwe, cve, severity, status, title, description,
                newFileId, unitId, symId, stmtId, loc, codeFlow, newFingerprint, rootCauseRef, blastRadiusRef,
                evidenceRefs, firstSeenRun, lastSeenRun, resolution, platformRequirement, affectedSymbols,
                affectedFiles, quality);
    }
}
