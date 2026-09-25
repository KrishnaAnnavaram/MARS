package com.mars.harness.kernel.engine;

import com.mars.harness.kernel.core.validation.DimensionStatus;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a VRH verification chain onto unified validation dimensions without changing its meaning.
 *
 * <ul>
 *   <li>re-scan: FIXED is PASS, STILL_VULNERABLE is FAIL, INCONCLUSIVE is INSUFFICIENT_EVIDENCE</li>
 *   <li>red-team: NO_BYPASS_FOUND is PASS, BYPASS_FOUND is FAIL, INCONCLUSIVE is
 *       INSUFFICIENT_EVIDENCE</li>
 *   <li>QA gate: Passed is PASS, Failed is FAIL, Refused is NOT_RUN</li>
 *   <li>build gate: Passed is PASS, Failed is FAIL, Refused is NOT_RUN</li>
 * </ul>
 *
 * <p>Nothing is upgraded. INCONCLUSIVE stays unknown, and a Refused gate stays "did not run".
 */
final class SecurityDimensions {

    private SecurityDimensions() {
    }

    static List<ValidationResult.DimensionResult> from(RemediationCapability.VerificationReport report) {
        List<ValidationResult.DimensionResult> dims = new ArrayList<>();
        String who = "security:" + report.findingId();
        dims.add(new ValidationResult.DimensionResult(ValidationDimension.SECURITY_RESCAN, verdict(report.rescan(),
                "FIXED", "STILL_VULNERABLE"), report.rescan(), report.findingId() + " re-scan " + report.rescan() + ": "
                + report.rescanReason(), report.evidenceRefs(), who));
        dims.add(new ValidationResult.DimensionResult(ValidationDimension.RED_TEAM, verdict(report.redteam(),
                "NO_BYPASS_FOUND", "BYPASS_FOUND"), report.redteam(), report.findingId() + " red-team " + report.redteam()
                + " over " + report.attemptedVectors().size() + " vector(s)", report.evidenceRefs(), who));
        dims.add(new ValidationResult.DimensionResult(ValidationDimension.TESTS, gate(report.qa()), report.qa(),
                report.findingId() + " QA gate " + report.qa(), report.evidenceRefs(), who));
        dims.add(new ValidationResult.DimensionResult(ValidationDimension.BUILD_PACKAGE, gate(report.build()), report.build(),
                report.findingId() + " build gate " + report.build(), report.evidenceRefs(), who));
        return dims;
    }

    private static DimensionStatus verdict(String value, String pass, String fail) {
        if (pass.equals(value)) {
            return DimensionStatus.PASS;
        }
        if (fail.equals(value)) {
            return DimensionStatus.FAIL;
        }
        return value == null ? DimensionStatus.NOT_RUN : DimensionStatus.INSUFFICIENT_EVIDENCE;
    }

    private static DimensionStatus gate(String value) {
        if ("Passed".equals(value)) {
            return DimensionStatus.PASS;
        }
        if ("Failed".equals(value)) {
            return DimensionStatus.FAIL;
        }
        if ("TOOL_UNAVAILABLE".equals(value)) {
            return DimensionStatus.TOOL_UNAVAILABLE;
        }
        return DimensionStatus.NOT_RUN;
    }
}
