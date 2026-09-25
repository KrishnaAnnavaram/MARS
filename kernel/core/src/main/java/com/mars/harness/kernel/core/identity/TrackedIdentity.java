package com.mars.harness.kernel.core.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * Lineage fields shared by every sub-file identity.
 *
 * <p>Identity is allocated, and every descriptive field (name, signature, path, line, content) is
 * an attribute that may change without the identity changing. Deletion, merge and split are
 * recorded here, never by removing the record.
 */
public abstract class TrackedIdentity {

    public IdentityStatus status = IdentityStatus.ACTIVE;
    public String fileId;
    public Location baselineLocation;
    public Location currentLocation;
    /** Scan-local observation key the record was last matched under. It is not an identity. */
    public String currentKey;
    public String createdByChange;
    public String deletedByChange;
    public String splitFrom;
    public String mergedInto;
    public List<String> changeIds = new ArrayList<>();
    public List<IdentityVersion> versions = new ArrayList<>();
    public List<ReattachmentEvidence> reattachment = new ArrayList<>();

    public abstract String id();

    public boolean active() {
        return status == IdentityStatus.ACTIVE;
    }

    /** True when any reattachment in this identity's history was attached with LOW certainty. */
    public boolean everUncertain() {
        return reattachment.stream().anyMatch(ReattachmentEvidence::uncertain);
    }

    public void recordChange(String changeId) {
        if (changeId != null && !changeIds.contains(changeId)) {
            changeIds.add(changeId);
        }
    }
}
