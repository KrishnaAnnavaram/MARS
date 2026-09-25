package com.bootshift.stages.stage02;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.BuildSystemResolver;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agent 02 - Build Resolver (spec section 12).
 *
 * <p>Asks the build tool what the build actually is. XML parsing is never the authority: when Maven
 * or Gradle cannot be invoked, the model is published with {@code authoritative=false} and a
 * blocking resolution issue, so no downstream stage can mistake a descriptor hint for a resolved
 * dependency graph (R5).
 *
 * <p>Unresolved required artifacts become blocking issues or explicit blind spots according to
 * policy - they are never quietly substituted.
 */
public final class BuildResolverStage implements Stage {

    public static final String OUTPUT_DIR = "02-build";

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Obtain the authoritative effective build model from Maven or Gradle itself";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.FILE_REGISTRY_SEALED);
    }

    @Override
    public RunState postcondition() {
        return RunState.BUILD_RESOLVED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("01-inventory/inventory-artifact.json", "01-inventory/file-registry.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("build-model.json", "dependency-model.json", "bom-model.json",
                "plugin-model.json", "repository-model.json", "resolution-issues.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        StageSupport.requireUpstream(context, "01-inventory", "inventory-artifact.json",
                "Run: harness inventory --repo <path>");

        Path root = context.run().originalWorkspace();
        if (!Files.isDirectory(root)) {
            root = context.run().sourceRoot();
        }
        Path evidenceSink = context.run().runWorkspace().resolve("build-evidence");

        BuildSystemResolver resolver = new BuildSystemResolver();
        BuildSystemPort.Kind detected = resolver.detect(root);
        if (detected == BuildSystemPort.Kind.UNKNOWN) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STRUCTURED_REFUSAL,
                    "No Maven or Gradle build was found under " + root,
                    List.of("Expected a pom.xml, build.gradle or per-service module directories"));
        }

        BuildSystemPort.BuildModel merged = resolver.resolve(root, evidenceSink);
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);

        long unresolved = merged.dependencies().stream()
                .filter(d -> !"RESOLVED".equals(d.resolutionStatus()))
                .count();

        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR)
                .stat("build_systems", resolver.bindings(root).size())
                .stat("detected_kind", detected.name())
                .stat("modules", merged.modules().size())
                .stat("dependencies", merged.dependencies().size())
                .stat("unresolved_dependencies", unresolved)
                .stat("authoritative", merged.authoritative());

        if (!merged.authoritative()) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-BUILD-001", "BUILD_MODEL",
                    "The build tool could not be invoked, so the dependency graph is descriptor-derived",
                    merged.degradedReason()));
        }
        if (detected == BuildSystemPort.Kind.MIXED) {
            envelope.gap(new Envelope.Gap("GAP-BUILD-002", "BUILD_SYSTEM",
                    "This repository contains both a Maven and a Gradle build",
                    "The composite is represented explicitly as MIXED and each module carries the "
                            + "provider that owns it; it is never flattened to a single build system"));
        }
        if (unresolved > 0) {
            envelope.gap(new Envelope.Gap("GAP-BUILD-001", "DEPENDENCY_RESOLUTION",
                    unresolved + " dependency coordinate(s) were not resolved by the build tool",
                    "Version-space and impact analysis for those coordinates is unreliable"));
        }

        // One serialized contract, written by the codec. Every later stage rehydrates through the
        // same codec instead of rebuilding a partial copy that silently loses managed versions,
        // plugins, repositories, resolution issues and toolchain details.
        ObjectNode buildModel = BuildModelCodec.encode(merged);
        buildModel.set("java_versions", Json.toTree(javaVersions(merged)));
        buildModel.set("frameworks", Json.toTree(detectFrameworks(merged)));
        buildModel.put("detected_kind", detected.name());
        buildModel.put("build_model_fingerprint", BuildModelCodec.fingerprint(merged));
        ObjectNode buildArtifact = StageSupport.compose(envelope, buildModel);
        StageSupport.validate(context, writer, "build/build-model.schema.json",
                "build-model.json", buildArtifact);
        writer.write("build-model.json", buildArtifact);

        ObjectNode dependencyModel = Json.obj();
        dependencyModel.put("dependency_count", merged.dependencies().size());
        dependencyModel.put("unresolved_count", unresolved);
        dependencyModel.set("dependencies", Json.toTree(merged.dependencies()));
        dependencyModel.set("by_module", Json.toTree(groupByModule(merged)));
        writer.write("dependency-model.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), dependencyModel));

        ObjectNode bomModel = Json.obj();
        bomModel.put("managed_version_count", merged.managedVersions().size());
        bomModel.set("managed_versions", Json.toTree(merged.managedVersions()));
        bomModel.set("imported_boms", Json.toTree(importedBoms(merged)));
        writer.write("bom-model.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), bomModel));

        ObjectNode pluginModel = Json.obj();
        pluginModel.put("plugin_count", merged.plugins().size());
        pluginModel.set("plugins", Json.toTree(merged.plugins()));
        writer.write("plugin-model.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), pluginModel));

        ObjectNode repositoryModel = Json.obj();
        repositoryModel.set("repositories", Json.toTree(merged.repositories()));
        writer.write("repository-model.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), repositoryModel));

        ObjectNode issues = Json.obj();
        issues.put("issue_count", merged.issues().size());
        issues.set("issues", Json.toTree(merged.issues()));
        writer.write("resolution-issues.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), issues));

        StageSupport.toEvidence(context, "build-model", buildArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Build model failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.BUILD_RESOLVED,
                merged.modules().size() + " modules resolved");
        context.runStateStore().updateState(context.run().runId(), RunState.BUILD_RESOLVED,
                "build model resolved");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (!merged.authoritative()) {
            messages.add("BUILD MODEL IS NOT AUTHORITATIVE: " + merged.degradedReason());
        }
        merged.issues().stream().filter(i -> "BLOCKING".equals(i.severity()))
                .forEach(i -> messages.add("BLOCKING: " + i.detail()));

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                merged.kind() + " model: " + merged.modules().size() + " module(s), "
                        + merged.dependencies().size() + " dependency record(s), "
                        + merged.managedVersions().size() + " managed version(s)"
                        + (merged.authoritative() ? "" : " [NOT AUTHORITATIVE]"),
                messages, artifacts, hash);
    }

    private Map<String, String> javaVersions(BuildSystemPort.BuildModel model) {
        Map<String, String> versions = new TreeMap<>();
        model.modules().forEach(m -> versions.put(m.moduleId(),
                m.javaVersion() == null ? "unspecified" : m.javaVersion()));
        return versions;
    }

    private Map<String, List<String>> groupByModule(BuildSystemPort.BuildModel model) {
        Map<String, List<String>> byModule = new TreeMap<>();
        model.dependencies().forEach(d -> byModule
                .computeIfAbsent(d.module(), k -> new ArrayList<>())
                .add(d.gav() + ":" + d.scope()));
        return byModule;
    }

    /**
     * Detects the framework coordinates that decide the migration edge. Only what the build model
     * actually contains - no inference from file names.
     */
    public static Map<String, String> detectFrameworks(BuildSystemPort.BuildModel model) {
        Map<String, String> frameworks = new TreeMap<>();
        for (BuildSystemPort.ModuleModel module : model.modules()) {
            if (module.parentGav() != null && module.parentGav().contains("spring-boot-starter-parent")) {
                frameworks.put("spring-boot", module.parentGav()
                        .substring(module.parentGav().lastIndexOf(':') + 1));
            }
            module.properties().forEach((key, value) -> {
                if (key.equals("spring-cloud.version")) {
                    frameworks.put("spring-cloud", value);
                }
                if (key.equals("java.version") || key.equals("maven.compiler.release")) {
                    frameworks.putIfAbsent("java", value);
                }
            });
        }
        for (BuildSystemPort.ResolvedDependency dependency : model.dependencies()) {
            switch (dependency.ga()) {
                case "org.springframework.boot:spring-boot" ->
                        frameworks.putIfAbsent("spring-boot", dependency.version());
                case "org.springframework:spring-core" ->
                        frameworks.putIfAbsent("spring-framework", dependency.version());
                case "org.springframework.security:spring-security-core" ->
                        frameworks.putIfAbsent("spring-security", dependency.version());
                case "org.hibernate:hibernate-core", "org.hibernate.orm:hibernate-core" ->
                        frameworks.putIfAbsent("hibernate", dependency.version());
                case "com.fasterxml.jackson.core:jackson-databind" ->
                        frameworks.putIfAbsent("jackson", dependency.version());
                case "org.springframework.data:spring-data-commons" ->
                        frameworks.putIfAbsent("spring-data", dependency.version());
                default -> {
                    // not a framework anchor
                }
            }
        }
        for (BuildSystemPort.ManagedVersion managed : model.managedVersions()) {
            if ("org.springframework.boot".equals(managed.groupId())
                    && "spring-boot-dependencies".equals(managed.artifactId())) {
                frameworks.putIfAbsent("spring-boot", managed.version());
            }
        }
        return frameworks;
    }

    private Set<String> importedBoms(BuildSystemPort.BuildModel model) {
        Set<String> boms = new LinkedHashSet<>();
        model.dependencies().stream()
                .filter(d -> "import".equals(d.scope()) || "pom".equals(d.type()))
                .forEach(d -> boms.add(d.gav()));
        model.managedVersions().stream()
                .filter(m -> m.artifactId().endsWith("-dependencies") || m.artifactId().endsWith("-bom"))
                .forEach(m -> boms.add(m.groupId() + ":" + m.artifactId() + ":" + m.version()));
        return boms;
    }
}
