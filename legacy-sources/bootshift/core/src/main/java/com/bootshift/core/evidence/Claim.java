package com.bootshift.core.evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * A defensible statement about the migration, bound to the evidence that supports it (spec 37).
 *
 * <p>A claim with no evidence references cannot be published: {@link #isPublishable()} is checked by
 * Agent 19 before the report is written.
 */
public final class Claim {

    private final String claimId;
    private final String dimension;
    private final String statement;
    private EvidenceLevel level = EvidenceLevel.E0;
    private CoverageStatement coverage;
    private final List<String> evidenceRefs = new ArrayList<>();
    private final List<String> approvalRefs = new ArrayList<>();
    private final List<String> blindSpotRefs = new ArrayList<>();
    private String status = "ASSERTED";

    public Claim(String claimId, String dimension, String statement) {
        this.claimId = claimId;
        this.dimension = dimension;
        this.statement = statement;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getDimension() {
        return dimension;
    }

    public String getStatement() {
        return statement;
    }

    public EvidenceLevel getLevel() {
        return level;
    }

    public Claim level(EvidenceLevel value) {
        this.level = value;
        return this;
    }

    public CoverageStatement getCoverage() {
        return coverage;
    }

    public Claim coverage(CoverageStatement value) {
        this.coverage = value;
        return this;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public Claim evidence(String ref) {
        if (ref != null) {
            evidenceRefs.add(ref);
        }
        return this;
    }

    public List<String> getApprovalRefs() {
        return approvalRefs;
    }

    public Claim approval(String ref) {
        approvalRefs.add(ref);
        return this;
    }

    public List<String> getBlindSpotRefs() {
        return blindSpotRefs;
    }

    public Claim blindSpot(String ref) {
        blindSpotRefs.add(ref);
        return this;
    }

    public String getStatus() {
        return status;
    }

    public Claim status(String value) {
        this.status = value;
        return this;
    }

    /** A claim is publishable only with at least one evidence reference and a coverage statement. */
    public boolean isPublishable() {
        return !evidenceRefs.isEmpty() && coverage != null;
    }

    public String render() {
        return claimId + " [" + dimension + " = " + level.name() + "] " + statement
                + " | Coverage: " + (coverage == null ? "MISSING" : coverage.render());
    }
}
