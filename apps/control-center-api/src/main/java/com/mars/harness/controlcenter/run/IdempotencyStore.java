package com.mars.harness.controlcenter.run;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Replays the response of a command already executed under the same {@code Idempotency-Key}.
 *
 * <p>This protects against a client retrying a request whose response it lost: the retry returns
 * what the first attempt did instead of attempting a second decision. The domain stays the real
 * guard (a gate accepts one decision; a proposal decision must name what it supersedes); this
 * store only makes retries quiet. It is in memory and bounded.
 */
@Component
public class IdempotencyStore {

    private static final int MAX_ENTRIES = 10_000;
    private static final Duration TTL = Duration.ofHours(24);

    private record Entry(String fingerprint, Object response, Instant at) {
    }

    /** A key reused for a different request is a client error, not a replay. */
    public static final class KeyReusedException extends RuntimeException {
        public KeyReusedException() {
            super("This Idempotency-Key was already used for a different request");
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized <T> Optional<T> replay(String scope, String key, String fingerprint, Class<T> type) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Entry entry = entries.get(scope + "|" + key);
        if (entry == null || entry.at().plus(TTL).isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new KeyReusedException();
        }
        return Optional.of(type.cast(entry.response()));
    }

    public synchronized void remember(String scope, String key, String fingerprint, Object response) {
        if (key != null && !key.isBlank()) {
            entries.put(scope + "|" + key, new Entry(fingerprint, response, Instant.now()));
        }
    }
}
