package com.bootshift.ports.compat;

import java.util.List;
import java.util.Optional;

/**
 * Tier-1 version-space knowledge acquisition (Agent 05).
 *
 * <p>Every externally retrieved fact is cached with provenance and a content hash. Community blog
 * posts are never accepted as hard compatibility assertions.
 */
public interface VersionSpacePort {

    /** All versions an artifact repository actually publishes for one coordinate. */
    record ArtifactVersions(String groupId, String artifactId, List<String> versions,
                            String repository, String retrievedAt, String contentHash, boolean online) {
    }

    /** Whether one exact coordinate exists. Used by the artifact-existence probe in Agent 08. */
    record ArtifactExistence(String groupId, String artifactId, String version, boolean exists,
                             String repository, String detail) {
    }

    /** A managed dependency entry taken from a published BOM. */
    record BomEntry(String groupId, String artifactId, String version) {
    }

    record BomSnapshot(String groupId, String artifactId, String version, List<BomEntry> entries,
                       String contentHash, String retrievedAt, boolean online) {
    }

    Optional<ArtifactVersions> versions(String groupId, String artifactId);

    ArtifactExistence exists(String groupId, String artifactId, String version);

    Optional<BomSnapshot> bom(String groupId, String artifactId, String version);

    /** Raw resource fetch used for configuration metadata and other published artifacts. */
    Optional<byte[]> fetchArtifactFile(String groupId, String artifactId, String version,
                                       String classifier, String extension);

    boolean online();
}
