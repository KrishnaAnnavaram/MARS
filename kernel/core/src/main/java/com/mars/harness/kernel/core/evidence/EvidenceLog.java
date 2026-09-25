package com.mars.harness.kernel.core.evidence;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.ids.HarnessIds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only, hash-chained evidence log ({@code provenance/evidence.jsonl}).
 *
 * <p>It uses the same chaining rule as Bootshift's ChangeLedger,
 * {@code HASH_N = SHA256(HASH_N-1 + CANONICAL_JSON_N)}, so tampering with any recorded fact is
 * detectable by {@link #verify(Path)}.
 */
public final class EvidenceLog {

    public static final String GENESIS = "0".repeat(64);

    private final Path file;
    private final Map<String, EvidenceRecord> byId = new LinkedHashMap<>();
    private String head = GENESIS;
    private final String runId;

    private EvidenceLog(Path file, String runId) {
        this.file = file;
        this.runId = runId;
    }

    public static EvidenceLog open(Path file, String runId) {
        EvidenceLog log = new EvidenceLog(file, runId);
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = KernelJson.parse(line);
                    EvidenceRecord record = KernelJson.convert(node.path("record"), EvidenceRecord.class);
                    log.byId.put(record.evidenceId(), record);
                    log.head = node.path("hash").asText();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read evidence log " + file, e);
            }
        }
        return log;
    }

    public synchronized EvidenceRecord record(EvidenceRecord.EvidenceKind kind, String summary, String observed,
                                              String where, List<String> subjects, String basis,
                                              EvidenceRecord.Reliability reliability, String asOf,
                                              String artifactRef, String artifactSha256, String producer) {
        EvidenceRecord record = new EvidenceRecord(HarnessIds.allocate(HarnessIds.Kind.EVIDENCE), runId, kind,
                summary, observed, where, subjects, basis, reliability, Instant.now().toString(), asOf,
                artifactRef, artifactSha256, producer);
        append(record);
        return record;
    }

    public synchronized void append(EvidenceRecord record) {
        String canonical = KernelJson.canonical(record);
        String hash = Hashing.chain(head, canonical);
        ObjectNode line = KernelJson.obj();
        line.put("previous_hash", head);
        line.put("hash", hash);
        line.set("record", KernelJson.tree(record));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, KernelJson.canonical(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append evidence " + file, e);
        }
        head = hash;
        byId.put(record.evidenceId(), record);
    }

    public Optional<EvidenceRecord> get(String evidenceId) {
        return Optional.ofNullable(byId.get(evidenceId));
    }

    public List<EvidenceRecord> all() {
        return new ArrayList<>(byId.values());
    }

    public String head() {
        return head;
    }

    public boolean contains(String evidenceId) {
        return byId.containsKey(evidenceId);
    }

    /** Recomputes the chain from disk. An empty list means the log is intact. */
    public static List<String> verify(Path file) {
        List<String> violations = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return violations;
        }
        String computed = GENESIS;
        int n = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                n++;
                JsonNode node = KernelJson.parse(line);
                if (!computed.equals(node.path("previous_hash").asText())) {
                    violations.add("Evidence line " + n + ": broken chain");
                }
                String expected = Hashing.chain(node.path("previous_hash").asText(),
                        KernelJson.canonical(node.path("record")));
                if (!expected.equals(node.path("hash").asText())) {
                    violations.add("Evidence line " + n + ": record content was modified");
                }
                computed = node.path("hash").asText();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violations;
    }
}
