package com.bootshift.ports.docs;

import java.util.List;
import java.util.Optional;

/**
 * Authoritative migration documentation retrieval and pinning (Agent 07).
 *
 * <p>No summary may replace the snapshot: the content-addressed body is retained so a later
 * reviewer can check what the harness actually read.
 */
public interface DocumentationPort {

    /** Trust ordering from spec section 18. Lower ordinal is more authoritative. */
    enum TrustLevel {
        OFFICIAL_MIGRATION_GUIDE,
        OFFICIAL_RELEASE_NOTES,
        OFFICIAL_METADATA,
        OFFICIAL_API_DOC,
        OFFICIAL_GENERAL_DOC,
        UPSTREAM_PROJECT_DOC,
        COMMUNITY_ADVISORY
    }

    /**
     * A pinned document. {@code sizeBytes} measures the raw snapshot, which is the authority;
     * {@code extractedTextLength} measures the readable rendition used for pattern extraction, and a
     * value near zero means the document was retrieved but nothing readable came out of it.
     */
    record DocumentRef(String documentId, String publisher, String component, String sourceVersion,
                       String targetVersion, String url, String retrievedAt, String contentHash,
                       TrustLevel trustLevel, String etag, String lastModified, boolean fromCache,
                       String localPath, String mediaType, int sizeBytes, int extractedTextLength) {
    }

    /** Retrieves and pins a document. Returns empty when the source is unreachable and uncached. */
    Optional<DocumentRef> retrieve(String url, String publisher, String component,
                                   String sourceVersion, String targetVersion, TrustLevel trustLevel);

    Optional<String> body(String documentId);

    List<DocumentRef> cached();

    boolean online();
}
