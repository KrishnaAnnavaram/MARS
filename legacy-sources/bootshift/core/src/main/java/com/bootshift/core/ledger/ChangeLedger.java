package com.bootshift.core.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, tamper-evident change ledger (spec section 27).
 *
 * <pre>
 * EVENT_HASH_N = SHA256(EVENT_HASH_N-1 + CANONICAL_EVENT_JSON_N)
 * </pre>
 *
 * <p>Insertion, deletion, reordering and in-place mutation are all detectable because every link
 * depends on the exact canonical bytes of every earlier event. The head hash is sealed into the
 * final evidence manifest, which is what makes the whole run auditable after the fact.
 */
public final class ChangeLedger {

    /** One persisted line: the event plus its chain metadata. */
    public record Entry(long sequence, String eventHash, String previousHash, ChangeEvent event) {
    }

    public static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";

    private final Path ledgerFile;
    private final Path headFile;
    private final List<Entry> entries = new ArrayList<>();
    private String head = GENESIS;
    private long sequence;

    public ChangeLedger(Path ledgerFile, Path headFile) {
        this.ledgerFile = ledgerFile;
        this.headFile = headFile;
    }

    public Path ledgerFile() {
        return ledgerFile;
    }

    public String head() {
        return head;
    }

    public long size() {
        return sequence;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Allocates the next CHANGE_ID without appending; used when a patch is only proposed. */
    public String nextChangeId() {
        return Ids.changeId(sequence + 1);
    }

    /**
     * Appends an event and advances the chain. The event is canonicalized first so the hash does not
     * depend on field ordering or pretty-printing.
     */
    public synchronized Entry append(ChangeEvent event) {
        long next = sequence + 1;
        event.setSequence(next);
        if (event.getChangeId() == null) {
            event.setChangeId(Ids.changeId(next));
        }
        if (event.getRecordedAt() == null) {
            event.setRecordedAt(Instant.now().toString());
        }
        String canonical = Json.canonical(event);
        String eventHash = Hashing.chain(head, canonical);
        Entry entry = new Entry(next, eventHash, head, event);
        entries.add(entry);
        head = eventHash;
        sequence = next;
        persist(entry);
        writeHead();
        return entry;
    }

    private void persist(Entry entry) {
        ObjectNode line = Json.obj();
        line.put("sequence", entry.sequence());
        line.put("previous_hash", entry.previousHash());
        line.put("event_hash", entry.eventHash());
        line.set("event", Json.toTree(entry.event()));
        try {
            if (ledgerFile.getParent() != null) {
                Files.createDirectories(ledgerFile.getParent());
            }
            Files.writeString(ledgerFile, Json.canonical(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to change ledger " + ledgerFile, e);
        }
    }

    private void writeHead() {
        ObjectNode node = Json.obj();
        node.put("head_hash", head);
        node.put("event_count", sequence);
        node.put("sealed_at", Instant.now().toString());
        Json.write(headFile, node);
    }

    /** Result of an independent verification pass over the persisted ledger file. */
    public record Verification(boolean valid, long verifiedEvents, String computedHead,
                               String recordedHead, List<String> violations) {
    }

    /**
     * Recomputes the whole chain from the persisted file. This never trusts the in-memory state, so
     * it detects offline tampering of the ledger file itself.
     */
    public static Verification verify(Path ledgerFile, Path headFile) {
        List<String> violations = new ArrayList<>();
        if (!Files.isRegularFile(ledgerFile)) {
            return new Verification(true, 0, GENESIS, GENESIS, List.of());
        }
        String computed = GENESIS;
        long count = 0;
        try {
            List<String> lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                count++;
                JsonNode node = Json.parse(line);
                long seq = node.path("sequence").asLong();
                if (seq != count) {
                    violations.add("Sequence discontinuity at line " + count + ": recorded " + seq);
                }
                String recordedPrevious = node.path("previous_hash").asText();
                if (!recordedPrevious.equals(computed)) {
                    violations.add("Broken chain at sequence " + seq
                            + ": expected previous " + computed + " but found " + recordedPrevious);
                }
                String canonicalEvent = Json.canonical(node.path("event"));
                String expectedHash = Hashing.chain(recordedPrevious, canonicalEvent);
                String recordedHash = node.path("event_hash").asText();
                if (!expectedHash.equals(recordedHash)) {
                    violations.add("Event " + seq + " hash mismatch: content was modified");
                }
                computed = recordedHash;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read change ledger " + ledgerFile, e);
        }

        String recordedHead = GENESIS;
        if (Files.isRegularFile(headFile)) {
            recordedHead = Json.read(headFile).path("head_hash").asText(GENESIS);
            long recordedCount = Json.read(headFile).path("event_count").asLong();
            if (recordedCount != count) {
                violations.add("Head declares " + recordedCount + " events but ledger holds " + count);
            }
        }
        if (!computed.equals(recordedHead)) {
            violations.add("Head hash mismatch: computed " + computed + " but head file records " + recordedHead);
        }
        return new Verification(violations.isEmpty(), count, computed, recordedHead, violations);
    }

    /** Reloads an existing ledger so a resumed run continues the same chain. */
    public static ChangeLedger reopen(Path ledgerFile, Path headFile) {
        ChangeLedger ledger = new ChangeLedger(ledgerFile, headFile);
        if (!Files.isRegularFile(ledgerFile)) {
            return ledger;
        }
        try {
            for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = Json.parse(line);
                ChangeEvent event = Json.convert(node.path("event"), ChangeEvent.class);
                Entry entry = new Entry(node.path("sequence").asLong(),
                        node.path("event_hash").asText(), node.path("previous_hash").asText(), event);
                ledger.entries.add(entry);
                ledger.head = entry.eventHash();
                ledger.sequence = entry.sequence();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reopen change ledger " + ledgerFile, e);
        }
        return ledger;
    }
}
