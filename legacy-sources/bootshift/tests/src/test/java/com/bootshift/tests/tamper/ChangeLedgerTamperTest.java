package com.bootshift.tests.tamper;

import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-injection tests for the tamper-evident ledger (spec section 58).
 *
 * <p>Each test mutates the persisted ledger in a way an attacker or a careless script might, and
 * asserts that verification notices. A hash chain that only detects the tampering you thought of is
 * not a hash chain, so the cases cover deletion, insertion, reordering and in-place edits
 * independently.
 */
class ChangeLedgerTamperTest {

    private ChangeLedger seed(Path directory, int events) {
        ChangeLedger ledger = new ChangeLedger(directory.resolve("change-ledger.jsonl"),
                directory.resolve("change-ledger-head.json"));
        for (int i = 1; i <= events; i++) {
            ledger.append(new ChangeEvent()
                    .setRunId("RUN-TEST")
                    .setEdgeId("EDGE-1")
                    .setFileId("FILE-" + i)
                    .setOperation(ChangeEvent.Operation.MODIFY)
                    .setPathBefore("module/src/main/java/Type" + i + ".java")
                    .setPathAfter("module/src/main/java/Type" + i + ".java")
                    .setBeforeSha256("a".repeat(64))
                    .setAfterSha256("b".repeat(64))
                    .setAgent("12-transformation")
                    .setProvider(new ChangeEvent.Provider("BOOTSHIFT_DETERMINISTIC", "test", "1.0.0"))
                    .setStatus(ChangeEvent.Status.APPLIED));
        }
        return ledger;
    }

    @Test
    @DisplayName("an untampered ledger verifies")
    void untamperedLedgerVerifies(@TempDir Path directory) {
        ChangeLedger ledger = seed(directory, 5);
        ChangeLedger.Verification verification = ChangeLedger.verify(
                directory.resolve("change-ledger.jsonl"), directory.resolve("change-ledger-head.json"));

        assertThat(verification.valid()).isTrue();
        assertThat(verification.verifiedEvents()).isEqualTo(5);
        assertThat(verification.computedHead()).isEqualTo(ledger.head());
        assertThat(verification.violations()).isEmpty();
    }

    @Test
    @DisplayName("deleting an event is detected")
    void deletionIsDetected(@TempDir Path directory) throws IOException {
        seed(directory, 5);
        Path file = directory.resolve("change-ledger.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.remove(2);
        Files.write(file, lines, StandardCharsets.UTF_8);

        ChangeLedger.Verification verification = ChangeLedger.verify(file,
                directory.resolve("change-ledger-head.json"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.violations())
                .anyMatch(v -> v.contains("Sequence discontinuity") || v.contains("Broken chain"));
    }

    @Test
    @DisplayName("reordering events is detected")
    void reorderingIsDetected(@TempDir Path directory) throws IOException {
        seed(directory, 5);
        Path file = directory.resolve("change-ledger.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        String moved = lines.remove(1);
        lines.add(3, moved);
        Files.write(file, lines, StandardCharsets.UTF_8);

        ChangeLedger.Verification verification = ChangeLedger.verify(file,
                directory.resolve("change-ledger-head.json"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.violations()).isNotEmpty();
    }

    @Test
    @DisplayName("editing an event in place is detected even when the chain links still line up")
    void inPlaceEditIsDetected(@TempDir Path directory) throws IOException {
        seed(directory, 4);
        Path file = directory.resolve("change-ledger.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        // Change what the event claims happened, leaving every recorded hash untouched.
        lines.set(1, lines.get(1).replace("\"status\":\"APPLIED\"", "\"status\":\"REVERTED\""));
        Files.write(file, lines, StandardCharsets.UTF_8);

        ChangeLedger.Verification verification = ChangeLedger.verify(file,
                directory.resolve("change-ledger-head.json"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.violations())
                .anyMatch(v -> v.contains("hash mismatch") && v.contains("content was modified"));
    }

    @Test
    @DisplayName("inserting a forged event is detected")
    void insertionIsDetected(@TempDir Path directory) throws IOException {
        seed(directory, 3);
        Path file = directory.resolve("change-ledger.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        String forged = lines.get(1)
                .replace("\"sequence\":2", "\"sequence\":2")
                .replace("FILE-2", "FILE-FORGED");
        lines.add(2, forged);
        Files.write(file, lines, StandardCharsets.UTF_8);

        ChangeLedger.Verification verification = ChangeLedger.verify(file,
                directory.resolve("change-ledger-head.json"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.violations()).isNotEmpty();
    }

    @Test
    @DisplayName("rewriting the head to match a truncated ledger is still detected")
    void truncationWithForgedHeadIsDetected(@TempDir Path directory) throws IOException {
        seed(directory, 5);
        Path file = directory.resolve("change-ledger.jsonl");
        Path head = directory.resolve("change-ledger-head.json");

        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        // Drop the last two events and forge the head to name the new final event hash.
        String survivingHash = com.bootshift.core.util.Json.parse(lines.get(2))
                .path("event_hash").asText();
        Files.write(file, lines.subList(0, 3), StandardCharsets.UTF_8);
        Files.writeString(head, "{\"head_hash\":\"" + survivingHash + "\",\"event_count\":5}",
                StandardCharsets.UTF_8);

        ChangeLedger.Verification verification = ChangeLedger.verify(file, head);

        // The chain itself is now internally consistent, so the count declared by the head is what
        // exposes the truncation.
        assertThat(verification.valid()).isFalse();
        assertThat(verification.violations())
                .anyMatch(v -> v.contains("declares 5 events but ledger holds 3"));
    }

    @Test
    @DisplayName("rejected and reverted attempts stay in history")
    void rejectedAttemptsArePreserved(@TempDir Path directory) {
        ChangeLedger ledger = new ChangeLedger(directory.resolve("change-ledger.jsonl"),
                directory.resolve("change-ledger-head.json"));
        ledger.append(new ChangeEvent().setRunId("RUN-TEST").setFileId("FILE-1")
                .setOperation(ChangeEvent.Operation.MODIFY)
                .setStatus(ChangeEvent.Status.APPLIED));
        ledger.append(new ChangeEvent().setRunId("RUN-TEST").setFileId("FILE-2")
                .setOperation(ChangeEvent.Operation.MODIFY)
                .setStatus(ChangeEvent.Status.REJECTED)
                .setRejectionReason("outside authorized scope"));
        ledger.append(new ChangeEvent().setRunId("RUN-TEST").setFileId("FILE-1")
                .setOperation(ChangeEvent.Operation.MODIFY)
                .setStatus(ChangeEvent.Status.REVERTED));

        assertThat(ledger.size()).isEqualTo(3);
        assertThat(ledger.entries()).extracting(e -> e.event().getStatus())
                .containsExactly(ChangeEvent.Status.APPLIED, ChangeEvent.Status.REJECTED,
                        ChangeEvent.Status.REVERTED);
        assertThat(ChangeLedger.verify(directory.resolve("change-ledger.jsonl"),
                directory.resolve("change-ledger-head.json")).valid()).isTrue();
    }

    @Test
    @DisplayName("a reopened ledger continues the same chain")
    void reopenContinuesChain(@TempDir Path directory) {
        ChangeLedger first = seed(directory, 3);
        String headAfterFirst = first.head();

        ChangeLedger reopened = ChangeLedger.reopen(directory.resolve("change-ledger.jsonl"),
                directory.resolve("change-ledger-head.json"));
        assertThat(reopened.head()).isEqualTo(headAfterFirst);
        assertThat(reopened.size()).isEqualTo(3);

        reopened.append(new ChangeEvent().setRunId("RUN-TEST").setFileId("FILE-4")
                .setOperation(ChangeEvent.Operation.MODIFY).setStatus(ChangeEvent.Status.APPLIED));

        assertThat(ChangeLedger.verify(directory.resolve("change-ledger.jsonl"),
                directory.resolve("change-ledger-head.json")).valid()).isTrue();
    }
}
