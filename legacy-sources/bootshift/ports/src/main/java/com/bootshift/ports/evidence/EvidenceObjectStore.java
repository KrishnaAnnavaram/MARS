package com.bootshift.ports.evidence;

import com.bootshift.core.evidence.EvidenceManifest;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Content-addressed evidence storage.
 *
 * <p>Object identity is the content hash regardless of backend, so a manifest sealed against a
 * filesystem store stays verifiable against an object-store backend holding the same bytes.
 */
public interface EvidenceObjectStore {

    record StoredObject(String evidenceId, String sha256, long sizeBytes, String relativePath) {
    }

    StoredObject put(String kind, String suggestedName, byte[] content,
                     EvidenceManifest.Classification classification, String retentionClass,
                     String producedBy);

    StoredObject putFile(String kind, Path file, EvidenceManifest.Classification classification,
                         String retentionClass, String producedBy);

    Optional<byte[]> get(String evidenceId);

    boolean exists(String evidenceId);

    Path root();
}
