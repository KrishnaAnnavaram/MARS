package com.mars.harness.kernel.engine.ledger;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;

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
 * The unified extension of Bootshift's change ledger (spec §19).
 *
 * <p>Bootshift's {@code ChangeLedger} stays the tamper-evident record of every file-level change
 * attempt, and its event schema is not modified. This companion chain, with the same hashing rule,
 * links each CHANGE_ID to everything that ledger cannot hold:
 *
 * <ul>
 *   <li>who or what proposed it, and why</li>
 *   <li>the evidence that supported it</li>
 *   <li>the human decision that approved it</li>
 *   <li>the symbol and statement identities it touched</li>
 *   <li>the validation that later confirmed or failed it</li>
 * </ul>
 */
public final class LineageLedger {

    public static final String GENESIS = "0".repeat(64);

    /** One lineage entry. {@code kind} is CHANGE, VALIDATION, REJECTION or REVERT. */
    public record Entry(long sequence, String kind, String changeId, String proposalId, String decisionId,
                        String capability, String provider, String actor, String reason, List<String> findingRefs,
                        List<String> migrationRefs, List<String> evidenceRefs, String fileId, String pathBefore,
                        String pathAfter, String hashBefore, String hashAfter, List<String> symbolIds,
                        List<String> statementIdsChanged, List<String> statementIdsCreated,
                        List<String> statementIdsDeleted, List<String> validationRefs, String status, String at) {
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private String head = GENESIS;

    public LineageLedger(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = KernelJson.parse(line);
                    entries.add(KernelJson.convert(node.path("entry"), Entry.class));
                    head = node.path("hash").asText();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public synchronized Entry append(Entry draft) {
        Entry entry = new Entry(entries.size() + 1L, draft.kind(), draft.changeId(), draft.proposalId(),
                draft.decisionId(), draft.capability(), draft.provider(), draft.actor(), draft.reason(),
                draft.findingRefs(), draft.migrationRefs(), draft.evidenceRefs(), draft.fileId(), draft.pathBefore(),
                draft.pathAfter(), draft.hashBefore(), draft.hashAfter(), draft.symbolIds(), draft.statementIdsChanged(),
                draft.statementIdsCreated(), draft.statementIdsDeleted(), draft.validationRefs(), draft.status(),
                draft.at() == null ? Instant.now().toString() : draft.at());
        String hash = Hashing.chain(head, KernelJson.canonical(entry));
        ObjectNode line = KernelJson.obj();
        line.put("previous_hash", head);
        line.put("hash", hash);
        line.set("entry", KernelJson.tree(entry));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, KernelJson.canonical(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append lineage " + file, e);
        }
        entries.add(entry);
        head = hash;
        return entry;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public String head() {
        return head;
    }

    public List<Entry> forChange(String changeId) {
        return entries.stream().filter(e -> changeId.equals(e.changeId())).toList();
    }

    public List<Entry> mentioning(String id) {
        return entries.stream().filter(e -> id.equals(e.changeId()) || id.equals(e.proposalId())
                || id.equals(e.decisionId()) || id.equals(e.fileId())
                || e.symbolIds() != null && e.symbolIds().contains(id)
                || e.findingRefs() != null && e.findingRefs().contains(id)
                || contains(e.statementIdsChanged(), id) || contains(e.statementIdsCreated(), id)
                || contains(e.statementIdsDeleted(), id)).toList();
    }

    private static boolean contains(List<String> list, String id) {
        return list != null && list.contains(id);
    }

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
                    violations.add("Lineage line " + n + ": broken chain");
                }
                if (!Hashing.chain(node.path("previous_hash").asText(), KernelJson.canonical(node.path("entry")))
                        .equals(node.path("hash").asText())) {
                    violations.add("Lineage line " + n + ": entry content was modified");
                }
                computed = node.path("hash").asText();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violations;
    }
}
