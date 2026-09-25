package com.bootshift.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Content addressing primitives. Every hash the harness emits is a lower-case hex SHA-256. */
public final class Hashing {

    private Hashing() {
    }

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256File(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Deterministic manifest hash over an ordered list of path-to-hash entries. Callers sort before
     * passing so the result is stable across filesystems and platforms.
     */
    /**
     * Keyed hash for correlating a sensitive value across runs without storing it.
     *
     * <p>HMAC rather than {@code sha256(key + value)}: the latter is length-extension vulnerable and
     * is not a keyed hash in any useful sense. It matters here because the values being keyed are
     * often low entropy, so an attacker who can grind candidates is the realistic threat and the
     * key is the only thing standing in the way.
     */
    public static String hmacSha256(String key, String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String manifestHash(List<String> sortedEntries) {
        MessageDigest digest = sha256Digest();
        for (String entry : sortedEntries) {
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 10);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Chain link used by the tamper-evident change ledger: SHA256(prev + canonicalJson). */
    public static String chain(String previousHash, String canonicalJson) {
        return sha256((previousHash == null ? "" : previousHash) + canonicalJson);
    }
}
