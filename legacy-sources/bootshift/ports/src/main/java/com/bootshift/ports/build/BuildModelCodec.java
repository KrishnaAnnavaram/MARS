package com.bootshift.ports.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single serialized contract for {@link BuildSystemPort.BuildModel}.
 *
 * <p>Stage 02 resolves the authoritative model once. Every later stage needs it again, and each one
 * used to rebuild a partial copy by hand from whichever artifacts it happened to read. Those copies
 * silently dropped managed versions, plugins, repositories, resolution issues and toolchain details,
 * so a stage that asked "what BOM manages this coordinate?" got an empty list and concluded nothing
 * managed it.
 *
 * <p>One writer and one reader, with a round-trip test asserting semantic equality, is what makes
 * "the build model survives the artifact plane" a checkable property rather than an intention.
 */
public final class BuildModelCodec {

    /** Bumped when the serialized shape changes in a way a reader must notice. */
    public static final String CONTRACT_VERSION = "2.0.0";

    private BuildModelCodec() {
    }

    // ------------------------------------------------------------------ encode

    /** Encodes the complete model. Nothing is elided; this is the artifact a later stage rehydrates. */
    public static ObjectNode encode(BuildSystemPort.BuildModel model) {
        ObjectNode node = Json.obj();
        node.put("build_model_contract_version", CONTRACT_VERSION);
        node.put("kind", model.kind().name());
        node.put("tool_version", model.toolVersion());
        node.put("tool_invocation", model.toolInvocation());
        node.put("wrapper_used", model.wrapperUsed());
        node.put("authoritative", model.authoritative());
        node.put("degraded_reason", model.degradedReason());
        node.set("toolchains", Json.toTree(model.toolchains()));
        node.set("modules", encodeModules(model.modules()));
        node.set("dependencies", encodeDependencies(model.dependencies()));
        node.set("plugins", encodePlugins(model.plugins()));
        node.set("managed_versions", encodeManaged(model.managedVersions()));
        node.set("repositories", encodeRepositories(model.repositories()));
        node.set("issues", encodeIssues(model.issues()));
        return node;
    }

    private static ArrayNode encodeModules(List<BuildSystemPort.ModuleModel> modules) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.ModuleModel module : modules) {
            ObjectNode node = Json.obj();
            node.put("moduleId", module.moduleId());
            node.put("path", module.path());
            node.put("groupId", module.groupId());
            node.put("artifactId", module.artifactId());
            node.put("version", module.version());
            node.put("packaging", module.packaging());
            node.put("parentGav", module.parentGav());
            node.put("javaVersion", module.javaVersion());
            node.put("buildKind", module.buildKind() == null
                    ? BuildSystemPort.Kind.UNKNOWN.name() : module.buildKind().name());
            node.set("properties", Json.toTree(module.properties()));
            node.set("activeProfiles", Json.toTree(module.activeProfiles()));
            node.set("classpath", Json.toTree(module.classpath()));
            array.add(node);
        }
        return array;
    }

    private static ArrayNode encodeDependencies(List<BuildSystemPort.ResolvedDependency> dependencies) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.ResolvedDependency dependency : dependencies) {
            ObjectNode node = Json.obj();
            node.put("groupId", dependency.groupId());
            node.put("artifactId", dependency.artifactId());
            node.put("version", dependency.version());
            node.put("type", dependency.type());
            node.put("scope", dependency.scope());
            node.put("module", dependency.module());
            node.put("checksum", dependency.checksum());
            node.put("repository", dependency.repository());
            node.put("direct", dependency.direct());
            node.put("managed", dependency.managed());
            node.put("bomSource", dependency.bomSource());
            node.put("resolutionStatus", dependency.resolutionStatus());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode encodePlugins(List<BuildSystemPort.ResolvedPlugin> plugins) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.ResolvedPlugin plugin : plugins) {
            ObjectNode node = Json.obj();
            node.put("groupId", plugin.groupId());
            node.put("artifactId", plugin.artifactId());
            node.put("version", plugin.version());
            node.put("module", plugin.module());
            node.put("phase", plugin.phase());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode encodeManaged(List<BuildSystemPort.ManagedVersion> managed) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.ManagedVersion version : managed) {
            ObjectNode node = Json.obj();
            node.put("groupId", version.groupId());
            node.put("artifactId", version.artifactId());
            node.put("version", version.version());
            node.put("source", version.source());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode encodeRepositories(List<BuildSystemPort.RepositoryRef> repositories) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.RepositoryRef repository : repositories) {
            ObjectNode node = Json.obj();
            node.put("id", repository.id());
            node.put("url", repository.url());
            node.put("snapshots", repository.snapshots());
            node.put("releases", repository.releases());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode encodeIssues(List<BuildSystemPort.ResolutionIssue> issues) {
        ArrayNode array = Json.arr();
        for (BuildSystemPort.ResolutionIssue issue : issues) {
            ObjectNode node = Json.obj();
            node.put("severity", issue.severity());
            node.put("module", issue.module());
            node.put("detail", issue.detail());
            node.put("remediation", issue.remediation());
            array.add(node);
        }
        return array;
    }

    // ------------------------------------------------------------------ decode

    /**
     * Rehydrates a model from the sealed artifact.
     *
     * <p>{@code dependencyNode} is accepted separately because Stage 02 publishes the dependency list
     * as its own artifact for readability. When it is null the dependencies are taken from the model
     * artifact itself, which is what a self-contained encode produces.
     */
    public static BuildSystemPort.BuildModel decode(JsonNode modelNode, JsonNode dependencyNode) {
        if (modelNode == null) {
            throw new IllegalArgumentException("Cannot decode a null build model artifact");
        }
        List<BuildSystemPort.ModuleModel> modules = new ArrayList<>();
        for (JsonNode module : modelNode.path("modules")) {
            Map<String, String> properties = new LinkedHashMap<>();
            module.path("properties").fields()
                    .forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
            List<String> profiles = strings(module.path("activeProfiles"));
            List<String> classpath = strings(module.path("classpath"));
            modules.add(new BuildSystemPort.ModuleModel(
                    module.path("moduleId").asText(),
                    module.path("path").asText(null),
                    text(module, "groupId"), text(module, "artifactId"), text(module, "version"),
                    module.path("packaging").asText("jar"),
                    text(module, "parentGav"), text(module, "javaVersion"),
                    properties, profiles, classpath,
                    kind(module.path("buildKind").asText(null), BuildSystemPort.Kind.MAVEN)));
        }

        JsonNode dependencySource = dependencyNode != null && dependencyNode.has("dependencies")
                ? dependencyNode : modelNode;
        List<BuildSystemPort.ResolvedDependency> dependencies = new ArrayList<>();
        for (JsonNode dependency : dependencySource.path("dependencies")) {
            dependencies.add(new BuildSystemPort.ResolvedDependency(
                    dependency.path("groupId").asText(),
                    dependency.path("artifactId").asText(),
                    text(dependency, "version"),
                    dependency.path("type").asText("jar"),
                    dependency.path("scope").asText("compile"),
                    dependency.path("module").asText("."),
                    text(dependency, "checksum"), text(dependency, "repository"),
                    dependency.path("direct").asBoolean(false),
                    dependency.path("managed").asBoolean(false),
                    text(dependency, "bomSource"),
                    dependency.path("resolutionStatus").asText("RESOLVED")));
        }

        // managed versions, plugins, repositories and issues are read from whichever artifact
        // carries them; Stage 02 publishes them separately as well as inside the model.
        List<BuildSystemPort.ManagedVersion> managed = new ArrayList<>();
        for (JsonNode version : firstPresent(modelNode, "managed_versions", "managedVersions")) {
            managed.add(new BuildSystemPort.ManagedVersion(
                    version.path("groupId").asText(), version.path("artifactId").asText(),
                    text(version, "version"), text(version, "source")));
        }
        List<BuildSystemPort.ResolvedPlugin> plugins = new ArrayList<>();
        for (JsonNode plugin : modelNode.path("plugins")) {
            plugins.add(new BuildSystemPort.ResolvedPlugin(
                    plugin.path("groupId").asText(), plugin.path("artifactId").asText(),
                    text(plugin, "version"), plugin.path("module").asText("."),
                    text(plugin, "phase")));
        }
        List<BuildSystemPort.RepositoryRef> repositories = new ArrayList<>();
        for (JsonNode repository : modelNode.path("repositories")) {
            repositories.add(new BuildSystemPort.RepositoryRef(
                    repository.path("id").asText(), repository.path("url").asText(),
                    repository.path("snapshots").asBoolean(false),
                    repository.path("releases").asBoolean(true)));
        }
        List<BuildSystemPort.ResolutionIssue> issues = new ArrayList<>();
        for (JsonNode issue : modelNode.path("issues")) {
            issues.add(new BuildSystemPort.ResolutionIssue(
                    issue.path("severity").asText("INFO"), text(issue, "module"),
                    text(issue, "detail"), text(issue, "remediation")));
        }

        Map<String, String> toolchains = new LinkedHashMap<>();
        modelNode.path("toolchains").fields()
                .forEachRemaining(e -> toolchains.put(e.getKey(), e.getValue().asText()));

        return new BuildSystemPort.BuildModel(
                kind(modelNode.path("kind").asText(null), BuildSystemPort.Kind.UNKNOWN),
                text(modelNode, "tool_version"), text(modelNode, "tool_invocation"),
                modelNode.path("wrapper_used").asBoolean(false),
                modules, dependencies, plugins, managed, repositories, issues, toolchains,
                modelNode.path("authoritative").asBoolean(false),
                text(modelNode, "degraded_reason"));
    }

    /** A stable fingerprint over what the model says, used by the round-trip test and provenance. */
    public static String fingerprint(BuildSystemPort.BuildModel model) {
        return Json.canonicalHash(encode(model));
    }

    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name)) {
                return node.get(name);
            }
        }
        return Json.arr();
    }

    private static BuildSystemPort.Kind kind(String raw, BuildSystemPort.Kind fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BuildSystemPort.Kind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
