package com.bootshift.core.security;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sensitive data policy (spec section 49).
 *
 * <p>Secrets are represented by metadata, never plaintext. The harness records that a value was
 * present, where it came from, and - only when policy explicitly permits - a keyed hash so two
 * observations can be compared for equality without either being readable.
 */
public final class SensitiveValues {

    public enum EvidencePolicy {
        NEVER_STORE_PLAINTEXT, KEYED_HASH_PERMITTED
    }

    private static final List<Pattern> SECRET_KEY_PATTERNS = List.of(
            Pattern.compile("(?i).*password.*"),
            Pattern.compile("(?i).*passwd.*"),
            Pattern.compile("(?i).*secret.*"),
            Pattern.compile("(?i).*token.*"),
            Pattern.compile("(?i).*api[-_.]?key.*"),
            Pattern.compile("(?i).*private[-_.]?key.*"),
            Pattern.compile("(?i).*credential.*"),
            Pattern.compile("(?i).*authorization.*"),
            Pattern.compile("(?i).*access[-_.]?key.*"),
            Pattern.compile("(?i).*client[-_.]?secret.*"),
            Pattern.compile("(?i).*keystore.*"),
            Pattern.compile("(?i).*truststore.*"),
            Pattern.compile("(?i).*salt.*"));

    /** URIs that embed credentials, e.g. mongodb+srv://user:pass@host. */
    private static final Pattern CREDENTIALED_URI =
            Pattern.compile("(?i)[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@");

    private static final Pattern AWS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private SensitiveValues() {
    }

    /** True when the property key alone is enough to treat the value as a secret. */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return SECRET_KEY_PATTERNS.stream().anyMatch(p -> p.matcher(normalized).matches());
    }

    /** True when the value itself looks like a credential regardless of its key. */
    public static boolean looksSensitive(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return CREDENTIALED_URI.matcher(value).find()
                || AWS_KEY.matcher(value).find()
                || PRIVATE_KEY_BLOCK.matcher(value).find();
    }

    /**
     * Produces the metadata representation of a sensitive value. {@code valueHash} stays null unless
     * the caller passes a policy key, which only the comparison paths that policy permits do.
     */
    public static ObjectNode describe(String key, String rawValue, String source, EvidencePolicy policy,
                                      String comparisonKey) {
        ObjectNode node = Json.obj();
        node.put("key", key);
        node.put("present", rawValue != null && !rawValue.isBlank());
        node.put("source", source);
        node.put("evidence_policy", policy.name());
        if (policy == EvidencePolicy.KEYED_HASH_PERMITTED && comparisonKey != null && rawValue != null) {
            // HMAC, not sha256(key + ":" + value). The values keyed here are often low entropy -
            // a port, a username, a short token - so the realistic attack is grinding candidates,
            // and a prefix-keyed digest also leaks to length extension. The key is the only thing
            // doing any work, so it has to be used as a key.
            node.put("value_hash", Hashing.hmacSha256(comparisonKey, rawValue));
            node.put("value_hash_algorithm", "HmacSHA256");
        } else {
            node.putNull("value_hash");
            node.putNull("value_hash_algorithm");
        }
        node.put("value_length", rawValue == null ? 0 : rawValue.length());
        return node;
    }

    /**
     * Redacts credentials embedded in a URI, keeping the shape and nothing else.
     *
     * <p>Both halves go. An earlier version kept the username so the URI stayed "inspectable", and
     * the corpus username duly appeared in a published artifact while the policy said no sensitive
     * value is ever stored. A username is half of a credential pair: on its own it is not usable,
     * and it narrows an attack from "guess two things" to "guess one".
     *
     * <p>What a reviewer needs from this string is the scheme and the host - enough to see what kind
     * of thing is configured and where it points. That survives; the identity does not.
     */
    public static String redactUri(String value) {
        if (value == null) {
            return null;
        }
        return CREDENTIALED_URI.matcher(value).replaceAll(m -> {
            String matched = m.group();
            int schemeEnd = matched.indexOf("://") + 3;
            return matched.substring(0, schemeEnd) + "REDACTED:REDACTED@";
        });
    }

    /** Full-line redaction used before any text is written to an artifact or a log. */
    public static String redactLine(String key, String line) {
        if (line == null) {
            return null;
        }
        String result = redactUri(line);
        result = AWS_KEY.matcher(result).replaceAll("AKIA****************");
        if (isSensitiveKey(key)) {
            int equals = result.indexOf('=');
            if (equals >= 0) {
                return result.substring(0, equals + 1) + "REDACTED";
            }
            return "REDACTED";
        }
        return result;
    }
}
