package com.mars.harness.kernel.core.identity;

/**
 * One point in an identity's lineage. For a statement, {@code label} holds the normalised
 * (redacted) text; for a symbol, the signature; for a program unit, the fully qualified name.
 */
public record IdentityVersion(String changeId, String label, String fingerprint, Location location,
                              String at, String reason) {
}
