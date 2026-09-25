package com.bootshift.stages.stage19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.BuildSystemResolver;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.bootstrap.RunBootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports the validated migration bundle (spec section 37).
 *
 * <p>The exported repository must correspond exactly to the sealed final source hash recorded in
 * {@code migration-result.json}. That is verified here rather than assumed: exporting a state
 * different from the one that passed validation would make every claim in the report false.
 */
public final class BundleExporter {

    public record Bundle(Path root, String exportedTreeHash, String recordedTreeHash,
                         boolean hashesMatch, int patchCount, int sbomComponents,
                         List<String> licenseFindings, String status, boolean validated,
                         String finalBuildModelFingerprint) {
    }

    /** What kind of export was requested. */
    public enum Mode {
        /** The default: only a completed, validated migration may be exported. */
        VALIDATED,
        /**
         * An explicitly requested diagnostic export of an incomplete run.
         *
         * <p>Marked as such inside the bundle. The point of a validated export is that possessing one
         * means something; an export that silently degrades to "whatever is on disk" when validation
         * did not pass removes that meaning entirely.
         */
        DIAGNOSTIC
    }

    private final StageContext context;

    public BundleExporter(StageContext context) {
        this.context = context;
    }

    public Bundle export(Path destination, String format) {
        return export(destination, format, Mode.VALIDATED);
    }

    public Bundle export(Path destination, String format, Mode mode) {
        JsonNode result = StageSupport.requireUpstream(context, "19-evidence",
                "migration-result.json", "Run: harness report");

        // A validated export means the migration completed and passed. Exporting anything else under
        // that name hands a reviewer a bundle whose existence implies a conclusion nobody reached.
        String status = result.path("status").asText("UNKNOWN");
        boolean validated = "MIGRATION_COMPLETE".equals(status);
        if (mode == Mode.VALIDATED && !validated) {
            throw HarnessException.block("Refusing a validated export: migration-result.json reports "
                    + "status " + status + ", not MIGRATION_COMPLETE. "
                    + describeWhyIncomplete(result)
                    + " Use the diagnostic export if an incomplete run needs to be inspected; it is "
                    + "labelled as such inside the bundle.");
        }

        // The pre-migration dependency model, kept only as the fallback basis for the SBOM when the
        // migrated build cannot be resolved authoritatively. It is never the preferred source.
        JsonNode dependencies = StageSupport.optionalUpstream(context, "02-build",
                "dependency-model.json");

        try {
            Files.createDirectories(destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create bundle directory " + destination, e);
        }

        Path migrationWorkspace = context.run().migrationWorkspace();
        Path gitDir = context.run().checkpointGit();

        // ---- migrated repository -----------------------------------------------------------------
        String exportedHash = null;
        if (!"patch".equalsIgnoreCase(format)) {
            Path repository = destination.resolve("migrated-repository");
            exportedHash = context.scm().snapshot(migrationWorkspace, repository,
                    RunBootstrap.SNAPSHOT_EXCLUDES);
        }
        String currentTreeHash = context.scm().currentTreeHash(migrationWorkspace, gitDir);
        String recordedTreeHash = result.path("final_source_tree_hash").asText(null);

        // A missing recorded hash used to compare equal to anything, so an export with no sealed
        // hash reported "hashes match: true". Absence of the thing that proves correspondence is
        // not proof of correspondence.
        if (recordedTreeHash == null || recordedTreeHash.isBlank()) {
            throw HarnessException.block("Refusing to export: migration-result.json records no "
                    + "final_source_tree_hash, so the exported repository cannot be shown to be the "
                    + "state that passed validation. A missing hash is a failure, not a match.");
        }
        boolean hashesMatch = recordedTreeHash.equals(currentTreeHash);
        if (!hashesMatch) {
            throw HarnessException.block("Refusing to export: the migration workspace tree hash "
                    + currentTreeHash + " does not match the sealed final hash " + recordedTreeHash
                    + " recorded in migration-result.json. The exported repository must be exactly the "
                    + "state that passed validation.");
        }

        // ---- migration.patch and patch series -----------------------------------------------------
        int patchCount = 0;
        String firstCheckpoint = firstCommit(migrationWorkspace, gitDir);
        if (firstCheckpoint != null && currentTreeHash != null) {
            String diff = context.scm().diff(migrationWorkspace, gitDir, firstCheckpoint, "HEAD");
            write(destination.resolve("migration.patch"), diff);
            List<String> series = context.scm().formatPatchSeries(migrationWorkspace, gitDir,
                    firstCheckpoint, "HEAD");
            Path seriesDir = destination.resolve("patch-series");
            for (int i = 0; i < series.size(); i++) {
                write(seriesDir.resolve(String.format("%04d.patch", i + 1)), series.get(i));
                patchCount++;
            }
        }

        // ---- reports and evidence -----------------------------------------------------------------
        copyLatest("19-evidence", "migration-report.md", destination);
        // The end-to-end migration document travels with the bundle: a reviewer receiving only the
        // export should still be able to see what happened, not just whether it was defensible.
        copyLatest("19-evidence", "MIGRATION_DOCUMENT.md", destination);
        copyLatest("19-evidence", "edge-evidence.json", destination);
        copyLatest("19-evidence", "coverage-statement.json", destination);
        copyLatest("19-evidence", "claims.json", destination);
        copyLatest("10-characterization", "characterization-scenarios.json", destination);
        copyLatest("17-differential", "differential-report.json", destination);
        copyLatest("20-provenance", "provenance-graph.json", destination);
        copyLatest("19-evidence", "migration-result.json", destination);
        copyLatest("19-evidence", "evidence-manifest.json", destination);
        copyLatest("14-graph-diff", "graph-diff.json", destination);
        copyLatest("14-graph-diff", "application-graph-current.json",
                destination.resolve("final-application-graph.json"));
        Path ledger = context.run().runWorkspace().resolve("change-ledger.jsonl");
        if (Files.isRegularFile(ledger)) {
            copy(ledger, destination.resolve("change-ledger.jsonl"));
        }

        ObjectNode validationSummary = Json.obj();
        validationSummary.put("run_id", context.run().runId());
        validationSummary.put("status", result.path("status").asText());
        validationSummary.set("evidence_shortfalls", result.path("evidence_shortfalls"));
        validationSummary.put("unexplained_differences", result.path("unexplained_differences").asInt());
        validationSummary.put("change_ledger_valid", result.path("change_ledger_valid").asBoolean());
        validationSummary.put("evidence_manifest_hash", result.path("evidence_manifest_hash").asText());
        validationSummary.put("exported_at", Instant.now().toString());
        Json.write(destination.resolve("validation-summary.json"), validationSummary);

        // ---- final resolved build model -------------------------------------------------------------
        // Resolved again, from the migrated workspace, after the last edge. The Stage 02 model
        // describes the application BEFORE the migration: generating an SBOM from it would describe
        // the dependency graph the migration replaced, which is exactly the artefact a consumer of
        // the bundle must not be handed.
        BuildSystemPort.BuildModel finalModel = new BuildSystemResolver()
                .resolve(migrationWorkspace, context.run().runWorkspace().resolve("final-build-evidence"));
        ObjectNode finalModelNode = BuildModelCodec.encode(finalModel);
        String finalFingerprint = BuildModelCodec.fingerprint(finalModel);
        finalModelNode.put("build_model_fingerprint", finalFingerprint);
        finalModelNode.put("resolved_from", "migrated workspace after the final edge");
        finalModelNode.put("authoritative", finalModel.authoritative());
        Json.write(destination.resolve("final-build-model.json"), finalModelNode);

        ObjectNode finalDependencies = Json.obj();
        finalDependencies.put("dependency_count", finalModel.dependencies().size());
        finalDependencies.set("dependencies", finalModelNode.path("dependencies"));
        Json.write(destination.resolve("final-dependency-model.json"), finalDependencies);

        // ---- SBOM (CycloneDX) and license report ---------------------------------------------------
        // Generated against the FINAL model when it is authoritative. When it is not, the SBOM says
        // so rather than quietly describing the pre-migration graph.
        JsonNode sbomSource = finalModel.authoritative() ? finalDependencies : dependencies;
        String sbomBasis = finalModel.authoritative()
                ? "final migrated resolved dependency graph"
                : "pre-migration dependency graph, because the migrated build model could not be "
                        + "resolved authoritatively: " + finalModel.degradedReason();
        int components = writeSbom(destination.resolve("sbom"), sbomSource, sbomBasis);
        List<String> licenseFindings = writeLicenseReport(destination.resolve("oss-license-report.json"),
                sbomSource, sbomBasis);

        ObjectNode exportManifest = Json.obj();
        exportManifest.put("mode", mode.name());
        exportManifest.put("validated", validated);
        exportManifest.put("status", status);
        exportManifest.put("recorded_tree_hash", recordedTreeHash);
        exportManifest.put("current_tree_hash", currentTreeHash);
        exportManifest.put("hashes_match", hashesMatch);
        exportManifest.put("final_build_model_fingerprint", finalFingerprint);
        exportManifest.put("final_build_model_authoritative", finalModel.authoritative());
        exportManifest.put("sbom_basis", sbomBasis);
        exportManifest.put("exported_at", Instant.now().toString());
        if (mode == Mode.DIAGNOSTIC) {
            exportManifest.put("warning", "DIAGNOSTIC EXPORT. This bundle is NOT a validated "
                    + "migration. The run reported status " + status + ".");
        }
        Json.write(destination.resolve("export-manifest.json"), exportManifest);

        return new Bundle(destination, exportedHash == null ? currentTreeHash : exportedHash,
                recordedTreeHash, hashesMatch, patchCount, components, licenseFindings, status,
                validated, finalFingerprint);
    }

    /** Turns the result's own numbers into the reason an export was refused. */
    private static String describeWhyIncomplete(JsonNode result) {
        List<String> reasons = new ArrayList<>();
        if (result.path("unexplained_differences").asLong(0) > 0) {
            reasons.add(result.path("unexplained_differences").asLong()
                    + " unexplained behavioural difference(s)");
        }
        if (result.path("unexpected_differences").asLong(0) > 0) {
            reasons.add(result.path("unexpected_differences").asLong()
                    + " unexpected behavioural difference(s)");
        }
        long outstanding = result.path("outstanding_approvals").asLong(0);
        if (outstanding > 0) {
            reasons.add(outstanding + " outstanding approval gate(s)");
        } else if (outstanding < 0) {
            reasons.add("approval state unknown: the approval stage never ran");
        }
        int shortfalls = result.path("evidence_shortfalls").size();
        if (shortfalls > 0) {
            reasons.add(shortfalls + " evidence shortfall(s)");
        }
        if (!result.path("all_edges_accounted_for").asBoolean(true)) {
            reasons.add(result.path("edges_complete").asInt() + " of "
                    + result.path("edges_planned").asInt() + " planned edge(s) complete");
        }
        return reasons.isEmpty() ? "" : "Reasons: " + String.join("; ", reasons) + ".";
    }

    // ------------------------------------------------------------------ SBOM

    /**
     * Writes a CycloneDX 1.5 SBOM for the migrated application.
     *
     * <p>Hand-built rather than plugin-generated on purpose: the harness must be able to produce an
     * SBOM for a repository it is not allowed to modify, and adding a plugin to the application POM
     * would be exactly that modification.
     */
    private int writeSbom(Path sbomDir, JsonNode dependencies, String basis) {
        ObjectNode sbom = Json.obj();
        sbom.put("bomFormat", "CycloneDX");
        sbom.put("specVersion", "1.5");
        sbom.put("serialNumber", "urn:uuid:" + java.util.UUID.nameUUIDFromBytes(
                context.run().runId().getBytes(StandardCharsets.UTF_8)));
        sbom.put("version", 1);

        ObjectNode metadata = Json.obj();
        metadata.put("timestamp", Instant.now().toString());
        ArrayNode tools = Json.arr();
        ObjectNode tool = Json.obj();
        tool.put("vendor", "Bootshift");
        tool.put("name", "bootshift");
        tool.put("version", "1.0.0");
        tools.add(tool);
        metadata.set("tools", tools);
        metadata.put("basis", basis);
        sbom.set("metadata", metadata);

        ArrayNode components = Json.arr();
        Map<String, ObjectNode> unique = new LinkedHashMap<>();
        if (dependencies != null) {
            for (JsonNode dependency : dependencies.path("dependencies")) {
                String group = dependency.path("groupId").asText();
                String artifact = dependency.path("artifactId").asText();
                String version = dependency.path("version").asText(null);
                String key = group + ":" + artifact + ":" + version;
                if (unique.containsKey(key)) {
                    continue;
                }
                ObjectNode component = Json.obj();
                component.put("type", "library");
                component.put("group", group);
                component.put("name", artifact);
                component.put("version", version);
                component.put("purl", "pkg:maven/" + group + "/" + artifact
                        + (version == null ? "" : "@" + version));
                component.put("scope", "test".equals(dependency.path("scope").asText())
                        ? "excluded" : "required");
                unique.put(key, component);
                components.add(component);
            }
        }
        sbom.set("components", components);
        Json.write(sbomDir.resolve("bom.json"), sbom);
        return components.size();
    }

    /**
     * Writes the OSS license report for the application's dependencies.
     *
     * <p>The report records what the harness could and could not determine. A dependency whose
     * license it cannot resolve is UNKNOWN, and UNKNOWN is a finding, not a pass.
     */
    private List<String> writeLicenseReport(Path target, JsonNode dependencies, String basis) {
        LicensePolicy policy = context.policy().license();
        ObjectNode report = Json.obj();
        report.put("run_id", context.run().runId());
        report.put("generated_at", Instant.now().toString());
        report.put("policy_rule", "Unknown license is blocked until verified");
        report.put("basis", basis);
        report.put("scope_note", "Bootshift's own license, OpenRewrite's license, each recipe "
                + "module's license and these application dependency licenses are separate "
                + "classifications and are never harmonised with one another.");
        report.set("allowlist", Json.toTree(List.copyOf(policy.allowlist())));
        report.set("denylist", Json.toTree(List.copyOf(policy.denylist())));

        ArrayNode harness = Json.arr();
        List<LicensePolicy.Finding> harnessFindings = new ArrayList<>();
        for (RunBootstrap.HarnessComponent component : RunBootstrap.harnessComponents()) {
            LicensePolicy.Finding finding = policy.evaluate(component.coordinate(),
                    component.version(), component.license(), "harness-bill-of-materials");
            harnessFindings.add(finding);
            harness.add(Json.toTree(finding).deepCopy());
        }
        report.set("harness_components", harness);
        report.put("harness_gate", LicensePolicy.gatePasses(harnessFindings) ? "PASSED" : "BLOCKED");

        ArrayNode application = Json.arr();
        int unknown = 0;
        if (dependencies != null) {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (JsonNode dependency : dependencies.path("dependencies")) {
                String coordinate = dependency.path("groupId").asText() + ":"
                        + dependency.path("artifactId").asText();
                if (seen.putIfAbsent(coordinate, true) != null) {
                    continue;
                }
                ObjectNode node = Json.obj();
                node.put("component", coordinate);
                node.put("version", dependency.path("version").asText(null));
                node.put("declared_license", (String) null);
                node.put("verdict", "UNKNOWN");
                node.put("reason", "License metadata for application dependencies is not resolved by "
                        + "this run; the strict-OSS gate covers the harness components, and the "
                        + "application dependency licenses are reported as unverified rather than "
                        + "assumed compliant");
                application.add(node);
                unknown++;
            }
        }
        report.set("application_components", application);
        report.put("application_components_unknown", unknown);
        Json.write(target, report);

        List<String> summary = new ArrayList<>();
        summary.add("harness gate: " + report.path("harness_gate").asText());
        summary.add(unknown + " application dependency license(s) unverified");
        return summary;
    }

    // ------------------------------------------------------------------ helpers

    private String firstCommit(Path workTree, Path gitDir) {
        try (var git = org.eclipse.jgit.api.Git.open(gitDir.toFile())) {
            var walk = git.log().call().iterator();
            String last = null;
            while (walk.hasNext()) {
                last = walk.next().getName();
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    private void copyLatest(String stage, String artifact, Path destination) {
        Path source = context.run().output().latestArtifactPath(stage, artifact);
        if (source != null) {
            copy(source, Files.isDirectory(destination)
                    ? destination.resolve(artifact) : destination);
        }
    }

    private void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setWritable(true, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy " + source + " to " + target, e);
        }
    }

    private void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    /** Content hash of the exported bundle, so the export itself is checkable. */
    public static String bundleHash(Path root) {
        return GitScmAdapter.computeContentManifestHash(root, List.of());
    }

    static String hashOf(String text) {
        return Hashing.sha256(text == null ? "" : text);
    }
}
