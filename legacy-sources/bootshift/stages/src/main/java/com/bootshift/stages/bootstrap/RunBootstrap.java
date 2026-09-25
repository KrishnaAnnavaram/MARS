package com.bootshift.stages.bootstrap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.scm.ScmPort;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure preflight - explicitly NOT an agent (spec section 5).
 *
 * <p>Bootstrap may validate the input path, verify OSS policy for the tools being loaded, create the
 * run id and workspaces, take the immutable original snapshot, create the mutable migration
 * worktree, capture source provenance and initialize output locations.
 *
 * <p>It may not perform migration analysis or make any migration decision. Inventory remains the
 * first analysis stage.
 */
public final class RunBootstrap {

    public static final String OUTPUT_DIR = "00-bootstrap";

    /** Third-party components the harness itself loads, checked against the license gate. */
    public record HarnessComponent(String coordinate, String version, String license, String role) {
    }

    private static final List<HarnessComponent> HARNESS_COMPONENTS = List.of(
            new HarnessComponent("com.fasterxml.jackson.core:jackson-databind", "2.17.2",
                    "Apache-2.0", "artifact plane serialization"),
            new HarnessComponent("com.fasterxml.jackson.datatype:jackson-datatype-jsr310", "2.17.2",
                    "Apache-2.0", "temporal serialization"),
            new HarnessComponent("com.fasterxml.jackson.dataformat:jackson-dataformat-xml", "2.17.2",
                    "Apache-2.0", "descriptor reading"),
            new HarnessComponent("com.networknt:json-schema-validator", "1.5.1",
                    "Apache-2.0", "artifact schema validation"),
            new HarnessComponent("info.picocli:picocli", "4.7.6", "Apache-2.0", "CLI"),
            new HarnessComponent("org.eclipse.jgit:org.eclipse.jgit", "6.10.0.202406032230-r",
                    "EDL-1.0", "snapshot, checkpoint and rename detection"),
            new HarnessComponent("com.github.javaparser:javaparser-symbol-solver-core", "3.26.2",
                    "Apache-2.0", "type-aware code model"),
            new HarnessComponent("org.slf4j:slf4j-api", "2.0.13", "MIT", "logging facade"),
            new HarnessComponent("org.slf4j:slf4j-simple", "2.0.13", "MIT", "logging binding"),
            new HarnessComponent("org.junit.jupiter:junit-jupiter", "5.10.3", "EPL-2.0", "harness tests"),
            new HarnessComponent("org.assertj:assertj-core", "3.26.3", "Apache-2.0", "harness tests"),
            new HarnessComponent("com.tngtech.archunit:archunit-junit5", "1.3.0",
                    "Apache-2.0", "architecture enforcement"),
            // OpenRewrite. Only the Apache-2.0 engine modules; the source-available Spring recipe
            // estate is on the forbidden-artifact list and is additionally refused at runtime by the
            // provider itself. Listing these here is what makes the gate cover what the harness
            // actually loads rather than what it loaded when the list was last edited.
            new HarnessComponent("org.openrewrite:rewrite-core", "8.90.4",
                    "Apache-2.0", "transformation engine"),
            new HarnessComponent("org.openrewrite:rewrite-java", "8.90.4",
                    "Apache-2.0", "type-aware Java transformation"),
            new HarnessComponent("org.openrewrite:rewrite-java-21", "8.90.4",
                    "Apache-2.0", "Java 21 language support for the transformation engine"),
            new HarnessComponent("org.openrewrite:rewrite-maven", "8.90.4",
                    "Apache-2.0", "structural Maven descriptor transformation"),
            new HarnessComponent("org.openrewrite:rewrite-yaml", "8.90.4",
                    "Apache-2.0", "YAML transformation"),
            new HarnessComponent("org.openrewrite:rewrite-properties", "8.90.4",
                    "Apache-2.0", "properties transformation"),
            new HarnessComponent("org.yaml:snakeyaml", "2.2",
                    "Apache-2.0", "structural YAML parsing for configuration migration"));

    /** Shared with every derived snapshot so workspace content hashes are directly comparable. */
    public static final List<String> SNAPSHOT_EXCLUDES = GitScmAdapter.DEFAULT_EXCLUDES;

    private final StageContext context;

    public RunBootstrap(StageContext context) {
        this.context = context;
    }

    public StageResult execute() {
        Path sourceRoot = context.run().sourceRoot();
        List<String> messages = new ArrayList<>();

        // 1. validate the requested repository path
        if (!Files.isDirectory(sourceRoot)) {
            throw HarnessException.refusal("Input path is not a directory: " + sourceRoot);
        }
        if (isInsideHarness(sourceRoot)) {
            messages.add("Input path is inside the harness repository; harness modules are excluded "
                    + "from analysis by the module filter.");
        }

        // 2. OSS policy gate for the tools being loaded
        LicensePolicy licensePolicy = context.policy().license();
        List<LicensePolicy.Finding> findings = new ArrayList<>();
        for (HarnessComponent component : HARNESS_COMPONENTS) {
            findings.add(licensePolicy.evaluate(component.coordinate(), component.version(),
                    component.license(), "harness-bill-of-materials"));
        }
        List<LicensePolicy.Finding> blocking = new ArrayList<>(LicensePolicy.blocking(findings));

        // A coordinate list says what the build declares. It cannot see a forbidden estate that
        // arrived transitively or was dropped onto the classpath, so the gate also asks the runtime.
        List<String> forbiddenLoadable = new ArrayList<>();
        for (String marker : LicensePolicy.forbiddenRecipeMarkerClasses()) {
            try {
                Class.forName(marker, false, RunBootstrap.class.getClassLoader());
                forbiddenLoadable.add(marker);
            } catch (ClassNotFoundException | LinkageError e) {
                // Absent, which is the required state.
            }
        }
        forbiddenLoadable.forEach(marker -> blocking.add(new LicensePolicy.Finding(
                marker, null, "Source-Available", LicensePolicy.Verdict.BLOCKED,
                "A forbidden source-available recipe estate is loadable at runtime even though no "
                        + "declared coordinate names it", "runtime-classpath-probe")));

        // 3-6. workspaces, snapshots, provenance
        Path runWorkspace = context.run().runWorkspace();
        createDirectories(runWorkspace, context.run().originalWorkspace(),
                context.run().migrationWorkspace(), context.run().runtimeOldWorkspace(),
                context.run().runtimeNewWorkspace(), context.run().checkpointGit(),
                context.run().evidenceStore(), context.run().stateStore());

        ScmPort.SourceProvenance provenance = context.scm().captureProvenance(sourceRoot);
        String originalHash = context.scm().snapshot(sourceRoot, context.run().originalWorkspace(),
                SNAPSHOT_EXCLUDES);
        String migrationHash = context.scm().snapshot(sourceRoot, context.run().migrationWorkspace(),
                SNAPSHOT_EXCLUDES);
        if (!originalHash.equals(migrationHash)) {
            throw HarnessException.stageFailure(
                    "Original and migration snapshots diverged during bootstrap: " + originalHash
                            + " vs " + migrationHash, null);
        }

        // The internal checkpoint history lives in the external workspace, never in the user input.
        context.scm().initCheckpointRepository(context.run().migrationWorkspace(),
                context.run().checkpointGit(),
                "bootshift baseline snapshot for run " + context.run().runId());
        markOriginalReadOnly(context.run().originalWorkspace(), messages);

        context.runStateStore().createRun(context.run().runId(), Map.of(
                "source_root", sourceRoot.toString(),
                "workspace_root", context.run().workspaceRoot().toString(),
                "policy", context.policy().name()));

        // 7. initialize output and evidence locations
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);

        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR)
                .repoState(new Envelope.RepoState(provenance.kind(),
                        sourceRoot.toString().replace((char) 92, '/'), provenance.commitSha(),
                        provenance.treeSha(), provenance.branch(), provenance.contentManifestHash()))
                .stat("harness_components", HARNESS_COMPONENTS.size())
                .stat("license_findings_blocking", blocking.size());

        ObjectNode payload = Json.obj();
        payload.set("source_provenance", Json.toTree(provenance));
        payload.put("original_snapshot_hash", originalHash);
        payload.put("migration_snapshot_hash", migrationHash);
        payload.put("git_backed", context.scm().isGitBacked(sourceRoot));
        payload.put("native_git_available", new GitScmAdapter().nativeGitAvailable());

        ObjectNode workspaces = Json.obj();
        workspaces.put("run_workspace", runWorkspace.toString().replace((char) 92, '/'));
        workspaces.put("original", context.run().originalWorkspace().toString().replace((char) 92, '/'));
        workspaces.put("migration", context.run().migrationWorkspace().toString().replace((char) 92, '/'));
        workspaces.put("runtime_old", context.run().runtimeOldWorkspace().toString().replace((char) 92, '/'));
        workspaces.put("runtime_new", context.run().runtimeNewWorkspace().toString().replace((char) 92, '/'));
        workspaces.put("checkpoint_git", context.run().checkpointGit().toString().replace((char) 92, '/'));
        workspaces.put("external_to_harness_repository", !isInsideHarness(runWorkspace));
        payload.set("workspaces", workspaces);

        ObjectNode license = Json.obj();
        license.put("gate", blocking.isEmpty() ? "PASSED" : "BLOCKED");
        license.set("allowlist", Json.toTree(List.copyOf(licensePolicy.allowlist())));
        license.set("denylist", Json.toTree(List.copyOf(licensePolicy.denylist())));
        license.set("forbidden_artifacts", Json.toTree(List.copyOf(licensePolicy.forbiddenArtifacts())));
        license.set("findings", Json.toTree(findings));
        license.set("forbidden_recipe_packages",
                Json.toTree(LicensePolicy.forbiddenRecipePackages()));
        license.set("forbidden_estates_loadable_at_runtime", Json.toTree(forbiddenLoadable));
        license.put("runtime_probe", "The declared coordinate list cannot see an estate that arrived "
                + "transitively, so the classpath is probed as well.");
        license.put("license_scope_note", "Bootshift is MIT. OpenRewrite is Apache-2.0. Each recipe "
                + "module and every application dependency carries its own classification. These are "
                + "never harmonised with one another.");
        payload.set("oss_license_gate", license);
        payload.set("messages", Json.toTree(messages));

        ObjectNode artifact = StageSupport.compose(envelope, payload);
        writer.write("bootstrap.json", artifact);
        writer.write("source-provenance.json", StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), (ObjectNode) Json.toTree(
                        Map.of("provenance", provenance, "original_snapshot_hash", originalHash))));
        writer.write("oss-license-gate.json", StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), license.deepCopy()));

        StageSupport.toEvidence(context, "bootstrap", artifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!blocking.isEmpty()) {
            StageSupport.publish(context, writer);
            throw HarnessException.block("Strict-OSS license gate blocked "
                    + blocking.size() + " harness component(s): " + blocking);
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.WORKSPACE_READY, "bootstrap created workspaces");
        context.stateMachine().transition(RunState.OSS_POLICY_VERIFIED, "license gate passed");
        context.runStateStore().updateState(context.run().runId(), RunState.OSS_POLICY_VERIFIED,
                "bootstrap complete");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("bootstrap.json", writer.dir().resolve("bootstrap.json"));
        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Workspaces created under " + runWorkspace + "; OSS gate passed for "
                        + HARNESS_COMPONENTS.size() + " harness components",
                messages, artifacts, hash);
    }

    public static List<HarnessComponent> harnessComponents() {
        return HARNESS_COMPONENTS;
    }

    private boolean isInsideHarness(Path candidate) {
        Path harness = context.harnessRoot().toAbsolutePath().normalize();
        return candidate.toAbsolutePath().normalize().startsWith(harness);
    }

    /**
     * Makes the original snapshot read-only. It is held for the entire run because differential
     * validation needs OLD, and nothing is permitted to mutate it.
     */
    private void markOriginalReadOnly(Path original, List<String> messages) {
        try (var stream = Files.walk(original)) {
            stream.filter(Files::isRegularFile).forEach(p -> p.toFile().setWritable(false, false));
        } catch (IOException e) {
            messages.add("Could not mark the original snapshot read-only: " + e.getMessage()
                    + " (integrity is still checked by the sealed content manifest hash)");
        }
    }

    private static void createDirectories(Path... paths) {
        for (Path path : paths) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create workspace directory " + path, e);
            }
        }
    }
}
