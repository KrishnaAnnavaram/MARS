package com.bootshift.tests.security;

import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.mutation.MutationPort;
import com.bootshift.ports.transformation.TransformationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure-injection tests for the mutation boundary (spec section 58).
 *
 * <p>The gateway is the single architectural enforcement point, so these tests attack it directly:
 * mutating before the baseline seal, writing outside the authorized scope, writing outside the
 * workspace, and writing behind the gateway's back.
 */
class MutationBoundaryTest {

    @TempDir
    Path root;

    private Path workspace;
    private Path gitDir;
    private Path patches;
    private FileRegistry registry;
    private ChangeLedger ledger;
    private String fileId;
    private boolean sealed;

    @BeforeEach
    void setUp() throws IOException {
        workspace = root.resolve("migration");
        gitDir = root.resolve("checkpoint-git");
        patches = root.resolve("patches");
        Files.createDirectories(workspace.resolve("mod/src/main/java"));
        Files.writeString(workspace.resolve("mod/src/main/java/A.java"),
                "package a;\nclass A {\n    void x() {}\n}\n", StandardCharsets.UTF_8);

        registry = new FileRegistry();
        String content = Files.readString(workspace.resolve("mod/src/main/java/A.java"));
        fileId = registry.allocate(new FileRegistry.ObservedFile("mod",
                "mod/src/main/java/A.java", Hashing.sha256(content), FileRole.JAVA_MAIN,
                content.length(), content)).fileId();

        ledger = new ChangeLedger(root.resolve("change-ledger.jsonl"),
                root.resolve("change-ledger-head.json"));
        sealed = true;
    }

    private FileMutationGateway gateway() {
        return new FileMutationGateway("RUN-TEST", workspace, gitDir, patches, registry, ledger,
                new GitScmAdapter(), new FileMutationGateway.BaselineSealVerifier() {
            @Override
            public boolean sealed() {
                return sealed;
            }

            @Override
            public String sealHash() {
                return "seal";
            }
        });
    }

    private MutationPort.Authorization authorize(Set<String> fileIds) {
        return new MutationPort.Authorization("EDGE-TEST", "12-transformation", fileIds, Set.of(),
                List.of("MK-00001"), List.of("IMPACT-00001"), 0, 0, false, false, false);
    }

    private TransformationPort.ProposedChange change(String path, String content) {
        return new TransformationPort.ProposedChange(path, null, "MODIFY", content,
                "test change", "test.recipe", List.of("MK-00001"), List.of("IMPACT-00001"), Map.of());
    }

    private TransformationPort.ProposedChange changeFrom(String path, String content, String baseHash) {
        return new TransformationPort.ProposedChange(path, null, "MODIFY", content,
                "test change", "test.recipe", List.of("MK-00001"), List.of("IMPACT-00001"),
                Map.of("base_hash", baseHash));
    }

    @Test
    @DisplayName("a proposal computed from stale content is rejected, not silently applied over the newer content")
    void staleProposalIsRejected() throws IOException {
        Path target = workspace.resolve("mod/src/main/java/A.java");
        String original = Files.readString(target);

        // Recipe 1 rewrites the file. Recipe 2 was computed from the ORIGINAL content, before
        // recipe 1 ran - which is exactly what happens when two recipes target the same pom.xml
        // and both are handed to the gateway in one batch.
        String afterFirst = "package a;\nclass A {\n    void first() {}\n}\n";
        String staleSecond = "package a;\nclass A {\n    void second() {}\n}\n";

        MutationPort.BatchOutcome first = gateway().apply(authorize(Set.of(fileId)),
                List.of(changeFrom("mod/src/main/java/A.java", afterFirst, Hashing.sha256(original))),
                new ChangeEvent.Provider("TEST", "test", "1.0"));
        assertThat(first.applied()).isEqualTo(1);
        assertThat(Files.readString(target)).isEqualTo(afterFirst);

        MutationPort.BatchOutcome second = gateway().apply(authorize(Set.of(fileId)),
                List.of(changeFrom("mod/src/main/java/A.java", staleSecond, Hashing.sha256(original))),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(second.applied()).isZero();
        assertThat(second.rejected()).isEqualTo(1);
        assertThat(second.outcomes().get(0).reason()).contains("STALE_BASE_CONTENT");
        // The first recipe's work survives. This is the whole point: a lost change that the
        // ledger still reports as APPLIED is the one failure the ledger cannot survive.
        assertThat(Files.readString(target)).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a proposal computed from the current content applies normally")
    void freshProposalApplies() throws IOException {
        Path target = workspace.resolve("mod/src/main/java/A.java");
        String original = Files.readString(target);
        String updated = "package a;\nclass A {\n    void updated() {}\n}\n";

        MutationPort.BatchOutcome outcome = gateway().apply(authorize(Set.of(fileId)),
                List.of(changeFrom("mod/src/main/java/A.java", updated, Hashing.sha256(original))),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(Files.readString(target)).isEqualTo(updated);
    }

    @Test
    @DisplayName("mutation before the baseline seal is refused")
    void mutationBeforeBaselineSealFails() {
        sealed = false;

        assertThatThrownBy(() -> gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/A.java", "package a;\nclass A { void y() {} }\n")),
                new ChangeEvent.Provider("TEST", "test", "1.0")))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("before the baseline is sealed");
    }

    @Test
    @DisplayName("a change outside the authorized scope is rejected and recorded")
    void outOfScopeChangeIsRejected() throws IOException {
        Files.writeString(workspace.resolve("mod/src/main/java/B.java"), "package a; class B {}");
        String otherContent = Files.readString(workspace.resolve("mod/src/main/java/B.java"));
        registry.allocate(new FileRegistry.ObservedFile("mod", "mod/src/main/java/B.java",
                Hashing.sha256(otherContent), FileRole.JAVA_MAIN, otherContent.length(), otherContent));

        MutationPort.BatchOutcome outcome = gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/B.java", "package a; class B { int changed; }")),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.rejected()).isEqualTo(1);
        assertThat(outcome.outcomes().get(0).reason()).contains("outside the authorized scope");
        // The rejection is history, not a silent no-op.
        assertThat(ledger.size()).isEqualTo(1);
        assertThat(ledger.entries().get(0).event().getStatus())
                .isEqualTo(ChangeEvent.Status.REJECTED);
        assertThat(Files.readString(workspace.resolve("mod/src/main/java/B.java")))
                .isEqualTo(otherContent);
    }

    @Test
    @DisplayName("a path traversal outside the workspace is refused")
    void pathTraversalIsRefused() {
        assertThatThrownBy(() -> gateway().apply(authorize(Set.of(fileId)),
                List.of(new TransformationPort.ProposedChange("../../escaped.java", null, "MODIFY",
                        "malicious", "escape attempt", "test.recipe", List.of(), List.of(), Map.of())),
                new ChangeEvent.Provider("TEST", "test", "1.0")))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("outside the migration workspace");
    }

    @Test
    @DisplayName("an authorized change is applied, hashed, patched and chained")
    void authorizedChangeIsApplied() throws IOException {
        // A real run has an internal checkpoint repository; create one so the checkpoint path is
        // exercised rather than skipped.
        new GitScmAdapter().initCheckpointRepository(workspace, gitDir, "baseline snapshot");
        String updated = "package a;\nclass A {\n    void x() {}\n    void y() {}\n}\n";

        MutationPort.BatchOutcome outcome = gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/A.java", updated)),
                new ChangeEvent.Provider("BOOTSHIFT_DETERMINISTIC", "test", "1.0"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(Files.readString(workspace.resolve("mod/src/main/java/A.java"))).isEqualTo(updated);

        MutationPort.MutationOutcome applied = outcome.outcomes().get(0);
        assertThat(applied.afterSha256()).isEqualTo(Hashing.sha256(updated));
        assertThat(applied.beforeSha256()).isNotEqualTo(applied.afterSha256());
        assertThat(patches.resolve(applied.patchRef())).exists();
        assertThat(outcome.checkpointRef()).isNotNull().isNotEqualTo("CHECKPOINT_FAILED");

        assertThat(registry.byId(fileId).orElseThrow().getVersions()).hasSize(2);
        assertThat(ChangeLedger.verify(root.resolve("change-ledger.jsonl"),
                root.resolve("change-ledger-head.json")).valid()).isTrue();
    }

    @Test
    @DisplayName("a checkpoint failure is recorded rather than discarding applied work")
    void checkpointFailureIsRecorded() throws IOException {
        // No checkpoint repository exists here, so the checkpoint attempt cannot succeed.
        String updated = "package a;\nclass A {\n    void x() {}\n    void z() {}\n}\n";

        MutationPort.BatchOutcome outcome = gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/A.java", updated)),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.checkpointRef()).isEqualTo("CHECKPOINT_FAILED");
        assertThat(Files.readString(workspace.resolve("mod/src/main/java/A.java"))).isEqualTo(updated);
        // The loss of rollback capability is itself a ledger entry, not a swallowed exception.
        assertThat(ledger.entries()).anyMatch(e ->
                e.event().getStatus() == ChangeEvent.Status.FAILED_VALIDATION
                        && e.event().getRejectionReason().contains("rollback is unavailable"));
        assertThat(ChangeLedger.verify(root.resolve("change-ledger.jsonl"),
                root.resolve("change-ledger-head.json")).valid()).isTrue();
    }

    @Test
    @DisplayName("a no-op proposal is rejected rather than recorded as a change")
    void noOpIsRejected() {
        String same = "package a;\nclass A {\n    void x() {}\n}\n";

        MutationPort.BatchOutcome outcome = gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/A.java", same)),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.outcomes().get(0).reason()).contains("identical to current content");
    }

    @Test
    @DisplayName("a write that bypassed the gateway is detected")
    void bypassIsDetected() throws IOException {
        // Something writes directly into the migration workspace, exactly as a rogue tool would.
        Files.writeString(workspace.resolve("mod/src/main/java/A.java"),
                "package a;\nclass A {\n    void tampered() {}\n}\n", StandardCharsets.UTF_8);

        List<String> violations = gateway().detectBypass();

        assertThat(violations).anyMatch(v -> v.startsWith("BYPASS:")
                && v.contains("mod/src/main/java/A.java"));
    }

    @Test
    @DisplayName("an untracked file appearing in the workspace is detected")
    void untrackedFileIsDetected() throws IOException {
        Files.writeString(workspace.resolve("mod/src/main/java/Sneaky.java"),
                "package a; class Sneaky {}", StandardCharsets.UTF_8);

        List<String> violations = gateway().detectBypass();

        assertThat(violations).anyMatch(v -> v.startsWith("UNTRACKED:") && v.contains("Sneaky.java"));
    }

    @Test
    @DisplayName("a write through a symbolic link that escapes the workspace is refused")
    void symlinkEscapeIsRefused() throws IOException {
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "original", StandardCharsets.UTF_8);

        Path link = workspace.resolve("mod/src/main/java/Link.java");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows without developer mode cannot create links; the control is still present.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: " + e);
            return;
        }

        assertThatThrownBy(() -> gateway().apply(authorize(Set.of(fileId)),
                List.of(change("mod/src/main/java/Link.java", "overwritten")),
                new ChangeEvent.Provider("TEST", "test", "1.0")))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("symbolic link");

        // The file outside the workspace is untouched. A path check alone would not have stopped
        // this: the link's own path is entirely inside the workspace.
        assertThat(Files.readString(secret)).isEqualTo("original");
    }

    @Test
    @DisplayName("a clean workspace produces no bypass violations")
    void cleanWorkspaceIsClean() {
        assertThat(gateway().detectBypass()).isEmpty();
    }

    @Test
    @DisplayName("creation, deletion and rename require explicit authorization")
    void identityOperationsRequireAuthorization() {
        MutationPort.Authorization restrictive = new MutationPort.Authorization("EDGE-TEST",
                "12-transformation", Set.of(fileId), Set.of(), List.of(), List.of(), 0, 0,
                false, false, false);

        MutationPort.BatchOutcome creation = gateway().apply(restrictive,
                List.of(new TransformationPort.ProposedChange("mod/src/main/java/New.java", null,
                        "CREATE", "package a; class New {}", "new file", "test.recipe",
                        List.of(), List.of(), Map.of())),
                new ChangeEvent.Provider("TEST", "test", "1.0"));
        assertThat(creation.rejected()).isEqualTo(1);
        assertThat(creation.outcomes().get(0).reason()).contains("does not permit file creation");

        MutationPort.BatchOutcome deletion = gateway().apply(restrictive,
                List.of(new TransformationPort.ProposedChange("mod/src/main/java/A.java", null,
                        "DELETE", null, "delete", "test.recipe", List.of(), List.of(), Map.of())),
                new ChangeEvent.Provider("TEST", "test", "1.0"));
        assertThat(deletion.rejected()).isEqualTo(1);
        assertThat(deletion.outcomes().get(0).reason()).contains("does not permit file deletion");
    }

    @Test
    @DisplayName("a patch exceeding the authorized line budget is rejected")
    void oversizedPatchIsRejected() {
        MutationPort.Authorization budgeted = new MutationPort.Authorization("EDGE-TEST",
                "13-build-repair", Set.of(fileId), Set.of(), List.of(), List.of(), 1, 2,
                false, false, false);

        String sprawling = "package a;\n" + "// line\n".repeat(50) + "class A {}\n";

        MutationPort.BatchOutcome outcome = gateway().apply(budgeted,
                List.of(change("mod/src/main/java/A.java", sprawling)),
                new ChangeEvent.Provider("TEST", "test", "1.0"));

        assertThat(outcome.rejected()).isEqualTo(1);
        assertThat(outcome.outcomes().get(0).reason()).contains("exceeding the authorized budget");
    }
}
