package com.bootshift.tests.security;

import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.identity.FileStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controls on the single writer that were documented but not enforced.
 *
 * <p>Each test below corresponds to a control the gateway described in its own comments and did not
 * actually apply: a budget that only logged, a rename that authorized one of its two paths, a merge
 * that deleted whatever a proposal attribute named, and a prefix check that treated {@code foo} as a
 * prefix of {@code foobar}.
 */
class MutationHardeningTest {

    @TempDir
    Path root;

    private Path workspace;
    private FileRegistry registry;
    private ChangeLedger ledger;
    private String alphaId;
    private String betaId;

    @BeforeEach
    void setUp() throws IOException {
        workspace = root.resolve("migration");
        Files.createDirectories(workspace.resolve("foo/src"));
        Files.createDirectories(workspace.resolve("foobar/src"));
        write("foo/src/Alpha.java", "package foo;\nclass Alpha {\n}\n");
        write("foobar/src/Beta.java", "package foobar;\nclass Beta {\n}\n");

        registry = new FileRegistry();
        alphaId = register("foo", "foo/src/Alpha.java");
        betaId = register("foobar", "foobar/src/Beta.java");
        ledger = new ChangeLedger(root.resolve("ledger.jsonl"), root.resolve("head.json"));
    }

    private void write(String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String register(String module, String relative) throws IOException {
        String content = Files.readString(workspace.resolve(relative), StandardCharsets.UTF_8);
        return registry.allocate(new FileRegistry.ObservedFile(module, relative,
                Hashing.sha256(content), FileRole.JAVA_MAIN, content.length(), content)).fileId();
    }

    private FileMutationGateway gateway() {
        return new FileMutationGateway("RUN-TEST", workspace, root.resolve("git"),
                root.resolve("patches"), registry, ledger, new GitScmAdapter(),
                new FileMutationGateway.BaselineSealVerifier() {
                    @Override
                    public boolean sealed() {
                        return true;
                    }

                    @Override
                    public String sealHash() {
                        return "seal";
                    }
                });
    }

    private static TransformationPort.ProposedChange modify(String path, String content) {
        return new TransformationPort.ProposedChange(path, null, "MODIFY", content,
                "test", "recipe.test", List.of("MK-1"), List.of("IMPACT-1"), Map.of());
    }

    // ------------------------------------------------------------------ budget

    @Test
    @DisplayName("a batch over the file budget is rejected, not merely logged")
    void budgetIsEnforced() {
        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId, betaId), Set.of(), List.of(), List.of(),
                1, 0, false, false, false);

        MutationPort.BatchOutcome outcome = gateway().apply(authorization,
                List.of(modify("foo/src/Alpha.java", "package foo;\nclass Alpha { int a; }\n"),
                        modify("foobar/src/Beta.java", "package foobar;\nclass Beta { int b; }\n")),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.rejected()).isEqualTo(2);
        assertThat(outcome.outcomes()).allSatisfy(o ->
                assertThat(o.reason()).contains("BUDGET_EXCEEDED"));
        // And nothing was written.
        assertThat(readWorkspace("foo/src/Alpha.java")).doesNotContain("int a");
    }

    // ------------------------------------------------------------------ path prefix semantics

    @Test
    @DisplayName("an authorized prefix of foo does not authorize foobar")
    void prefixMatchingUsesPathSegments() {
        assertThat(FileMutationGateway.isPathPrefix("foo", "foo/src/Alpha.java")).isTrue();
        assertThat(FileMutationGateway.isPathPrefix("foo", "foo")).isTrue();
        // The whole point: string prefix matching says yes here, and it is a different module.
        assertThat(FileMutationGateway.isPathPrefix("foo", "foobar/src/Beta.java")).isFalse();
        assertThat(FileMutationGateway.isPathPrefix("foo", "foobar")).isFalse();
        assertThat(FileMutationGateway.isPathPrefix("", "anything")).isFalse();
    }

    @Test
    @DisplayName("a prefix authorization does not leak into a similarly named sibling")
    void prefixAuthorizationDoesNotLeak() {
        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(), Set.of("foo"), List.of(), List.of(),
                0, 0, false, false, false);

        MutationPort.BatchOutcome outcome = gateway().apply(authorization,
                List.of(modify("foobar/src/Beta.java", "package foobar;\nclass Beta { int b; }\n")),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.outcomes().get(0).reason()).contains("outside the authorized scope");
    }

    // ------------------------------------------------------------------ rename

    @Test
    @DisplayName("a rename must have its destination authorized as well as its source")
    void renameAuthorizesBothPaths() {
        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId), Set.of("foo"), List.of(), List.of(),
                0, 0, false, false, true);

        // Source is authorized; destination is in a module that is not.
        TransformationPort.ProposedChange escape = new TransformationPort.ProposedChange(
                "foo/src/Alpha.java", "foobar/src/Escaped.java", "RENAME", null, "test",
                "recipe.test", List.of(), List.of(), Map.of());

        MutationPort.BatchOutcome outcome = gateway().apply(authorization, List.of(escape),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.outcomes().get(0).reason())
                .contains("RENAME destination")
                .contains("outside the authorized scope");
        assertThat(Files.exists(workspace.resolve("foobar/src/Escaped.java"))).isFalse();
        assertThat(Files.exists(workspace.resolve("foo/src/Alpha.java"))).isTrue();
    }

    @Test
    @DisplayName("an authorized rename applies and keeps the FILE_ID")
    void authorizedRenameKeepsIdentity() {
        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId), Set.of("foo"), List.of(), List.of(),
                0, 0, false, false, true);

        TransformationPort.ProposedChange rename = new TransformationPort.ProposedChange(
                "foo/src/Alpha.java", "foo/src/Renamed.java", "RENAME", null, "test",
                "recipe.test", List.of(), List.of(), Map.of());

        MutationPort.BatchOutcome outcome = gateway().apply(authorization, List.of(rename),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.outcomes().get(0).fileId()).isEqualTo(alphaId);
        assertThat(registry.byId(alphaId).orElseThrow().getCurrentPath())
                .isEqualTo("foo/src/Renamed.java");
    }

    // ------------------------------------------------------------------ merge

    @Test
    @DisplayName("a merge source that is not registered is refused rather than deleted")
    void mergeSourceMustBeRegistered() throws IOException {
        write("foo/src/Ghost.java", "package foo;\nclass Ghost {}\n");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("merged_from", "foo/src/Ghost.java");

        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId), Set.of("foo"), List.of(), List.of(),
                0, 0, false, false, false);
        TransformationPort.ProposedChange merge = new TransformationPort.ProposedChange(
                "foo/src/Alpha.java", null, "MERGE", "package foo;\nclass Alpha { }\nclass Ghost {}\n",
                "test", "recipe.test", List.of(), List.of(), attributes);

        MutationPort.BatchOutcome outcome = gateway().apply(authorization, List.of(merge),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.outcomes().get(0).reason())
                .contains("MERGE source")
                .contains("no registered FILE_ID");
        // The unregistered file is still there. It was never authorized, so it is not deleted.
        assertThat(Files.exists(workspace.resolve("foo/src/Ghost.java"))).isTrue();
    }

    @Test
    @DisplayName("a merge source outside the authorized scope is refused")
    void mergeSourceMustBeInScope() throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("merged_from", "foobar/src/Beta.java");

        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId), Set.of("foo"), List.of(), List.of(),
                0, 0, false, false, false);
        TransformationPort.ProposedChange merge = new TransformationPort.ProposedChange(
                "foo/src/Alpha.java", null, "MERGE", "package foo;\nclass Alpha {}\n",
                "test", "recipe.test", List.of(), List.of(), attributes);

        MutationPort.BatchOutcome outcome = gateway().apply(authorization, List.of(merge),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isZero();
        assertThat(Files.exists(workspace.resolve("foobar/src/Beta.java"))).isTrue();
        assertThat(registry.byId(betaId).orElseThrow().getStatus()).isEqualTo(FileStatus.ACTIVE);
    }

    @Test
    @DisplayName("an authorized merge records lineage for every source")
    void authorizedMergeRecordsLineage() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("merged_from", "foobar/src/Beta.java");

        MutationPort.Authorization authorization = new MutationPort.Authorization("EDGE-1",
                "12-transformation", Set.of(alphaId, betaId), Set.of(), List.of(), List.of(),
                0, 0, false, false, false);
        TransformationPort.ProposedChange merge = new TransformationPort.ProposedChange(
                "foo/src/Alpha.java", null, "MERGE",
                "package foo;\nclass Alpha {}\nclass Beta {}\n", "test", "recipe.test",
                List.of(), List.of(), attributes);

        MutationPort.BatchOutcome outcome = gateway().apply(authorization, List.of(merge),
                new ChangeEvent.Provider("TEST", "test", "1"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(registry.byId(betaId).orElseThrow().getStatus())
                .isEqualTo(FileStatus.MERGED_AWAY);
        assertThat(registry.byId(betaId).orElseThrow().getMergedInto()).isEqualTo(alphaId);
        // Not simply the last entry: a checkpoint failure in this temporary workspace appends its
        // own event afterwards, and asserting on "the last one" would be asserting on that.
        ChangeEvent event = ledger.entries().stream()
                .map(ChangeLedger.Entry::event)
                .filter(e -> e.getOperation() == ChangeEvent.Operation.MERGE)
                .filter(e -> e.getStatus() == ChangeEvent.Status.APPLIED)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(event.getMergedInto()).contains(betaId);
    }

    // ------------------------------------------------------------------ patch format

    @Test
    @DisplayName("the patch is a real unified diff with hunk headers")
    void patchIsUnifiedDiff() {
        String before = "line1\nline2\nline3\nline4\nline5\nline6\nline7\n";
        String after = "line1\nline2\nCHANGED\nline4\nline5\nline6\nline7\n";

        String patch = FileMutationGateway.unifiedDiff("a/File.java", "a/File.java",
                before, after, 3);

        assertThat(patch).startsWith("--- a/a/File.java\n+++ b/a/File.java\n");
        assertThat(patch).contains("@@ -");
        assertThat(patch).contains("-line3");
        assertThat(patch).contains("+CHANGED");
        // Context lines are present and carry the single leading space a unified diff requires.
        assertThat(patch).contains(" line2");
        assertThat(patch).contains(" line4");
        // The unchanged tail beyond the context window is not in the patch.
        assertThat(patch).doesNotContain(" line7");
    }

    @Test
    @DisplayName("an unchanged file produces a header-only diff rather than a fake hunk")
    void unchangedProducesNoHunk() {
        String patch = FileMutationGateway.unifiedDiff("a/File.java", "a/File.java",
                "same\n", "same\n", 3);
        assertThat(patch).doesNotContain("@@");
    }

    private String readWorkspace(String relative) {
        try {
            return Files.readString(workspace.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
