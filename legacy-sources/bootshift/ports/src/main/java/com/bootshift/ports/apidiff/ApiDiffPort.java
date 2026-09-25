package com.bootshift.ports.apidiff;

import java.nio.file.Path;
import java.util.List;

/**
 * Binary and source API comparison between two artifact versions (Agent 08, artifact channel).
 *
 * <p>This is the half of migration knowledge that documentation cannot supply: what actually changed
 * in the published bytes.
 */
public interface ApiDiffPort {

    enum ChangeKind {
        TYPE_REMOVED, TYPE_ADDED, METHOD_REMOVED, METHOD_ADDED, METHOD_SIGNATURE_CHANGED,
        FIELD_REMOVED, FIELD_ADDED, TYPE_RELOCATED, MODIFIER_CHANGED
    }

    record ApiChange(ChangeKind kind, String type, String member, String oldSignature,
                     String newSignature, String detail) {
    }

    record DiffResult(String groupId, String artifactId, String oldVersion, String newVersion,
                      List<ApiChange> changes, String toolName, String toolVersion, boolean complete,
                      String incompleteReason) {
    }

    String name();

    boolean available();

    DiffResult compare(Path oldJar, Path newJar, String groupId, String artifactId,
                       String oldVersion, String newVersion);
}
