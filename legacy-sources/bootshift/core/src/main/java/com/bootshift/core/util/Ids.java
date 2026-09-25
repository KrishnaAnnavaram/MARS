package com.bootshift.core.util;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID-style monotonic identifier allocation.
 *
 * <p>Identity is <em>allocated</em>, never derived from path or content. See ADR-001: this is why a
 * FILE_ID survives renames and edits, and also why identity recovery depends on the integrity of
 * the File Registry.
 */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String ulid() {
        return ulid(Instant.now());
    }

    public static String ulid(Instant when) {
        long timestamp = when.toEpochMilli();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = CROCKFORD[RANDOM.nextInt(32)];
        }
        return new String(out);
    }

    public static String runId() {
        return "RUN-" + ulid();
    }

    public static String fileId() {
        return "FILE-" + ulid();
    }

    public static String symbolId() {
        return "SYM-" + ulid();
    }

    public static String changeId(long sequence) {
        return String.format("CHANGE-%06d", sequence);
    }

    public static String impactId(long sequence) {
        return String.format("IMPACT-%05d", sequence);
    }

    public static String knowledgeId(long sequence) {
        return String.format("MK-%05d", sequence);
    }

    public static String scenarioId(long sequence) {
        return String.format("SCN-%05d", sequence);
    }

    public static String decisionId(long sequence) {
        return String.format("DEC-%05d", sequence);
    }

    public static String documentId(long sequence) {
        return String.format("DOC-%05d", sequence);
    }

    public static String capabilityId(String provider, String slug) {
        return "CAP-" + provider.toUpperCase(java.util.Locale.ROOT) + "-" + slug.toUpperCase(java.util.Locale.ROOT);
    }
}
