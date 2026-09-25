package com.bootshift.adapters.build;

import com.bootshift.ports.build.BuildSystemPort;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Composition root for build providers.
 *
 * <p>Downstream stages used to write {@code new MavenBuildAdapter()} inline. That is a decision about
 * the repository under analysis, taken by a stage that never looked at it, and on a Gradle project it
 * produced a Maven invocation that failed for a reason having nothing to do with the migration.
 *
 * <p>This resolver detects what is actually present, routes each module to the provider that owns it,
 * and represents a Maven-plus-Gradle repository as {@link BuildSystemPort.Kind#MIXED} instead of
 * flattening it to MAVEN.
 */
public final class BuildSystemResolver {

    /** A provider bound to the repository it was detected in. */
    public record Binding(BuildSystemPort.Kind kind, BuildSystemPort provider) {
    }

    private final MavenBuildAdapter maven;
    private final GradleBuildAdapter gradle;

    public BuildSystemResolver() {
        this(new MavenBuildAdapter(), new GradleBuildAdapter());
    }

    public BuildSystemResolver(MavenBuildAdapter maven, GradleBuildAdapter gradle) {
        this.maven = maven;
        this.gradle = gradle;
    }

    public MavenBuildAdapter maven() {
        return maven;
    }

    public GradleBuildAdapter gradle() {
        return gradle;
    }

    /** What the repository actually contains, without invoking either tool. */
    public BuildSystemPort.Kind detect(Path repositoryRoot) {
        boolean hasMaven = maven.supports(repositoryRoot);
        boolean hasGradle = gradle.supports(repositoryRoot) || gradleModulePresent(repositoryRoot);
        if (hasMaven && hasGradle) {
            return BuildSystemPort.Kind.MIXED;
        }
        if (hasMaven) {
            return BuildSystemPort.Kind.MAVEN;
        }
        return hasGradle ? BuildSystemPort.Kind.GRADLE : BuildSystemPort.Kind.UNKNOWN;
    }

    /** Every provider that has something to resolve in this repository, in detection order. */
    public List<Binding> bindings(Path repositoryRoot) {
        List<Binding> bindings = new ArrayList<>();
        if (maven.supports(repositoryRoot)) {
            bindings.add(new Binding(BuildSystemPort.Kind.MAVEN, maven));
        }
        if (gradle.supports(repositoryRoot) || gradleModulePresent(repositoryRoot)) {
            bindings.add(new Binding(BuildSystemPort.Kind.GRADLE, gradle));
        }
        return bindings;
    }

    /**
     * The provider that owns a specific module.
     *
     * <p>Resolution is by what the module directory holds, not by the repository-wide kind: in a
     * composite repository the two answers differ, and using the repository-wide one is exactly the
     * bug this class exists to remove.
     */
    public Optional<BuildSystemPort> providerFor(BuildSystemPort.ModuleModel module, Path moduleRoot) {
        if (module != null && module.buildKind() == BuildSystemPort.Kind.GRADLE) {
            return Optional.of(gradle);
        }
        if (module != null && module.buildKind() == BuildSystemPort.Kind.MAVEN) {
            return Optional.of(maven);
        }
        if (moduleRoot == null) {
            return Optional.empty();
        }
        if (java.nio.file.Files.isRegularFile(moduleRoot.resolve("pom.xml"))) {
            return Optional.of(maven);
        }
        if (gradle.supports(moduleRoot)) {
            return Optional.of(gradle);
        }
        return Optional.empty();
    }

    /**
     * Resolves the complete model for a repository, merging providers when both are present.
     *
     * <p>The merged model keeps every provider's modules, dependencies, plugins, managed versions,
     * repositories and issues, and is authoritative only when every contributing provider was.
     */
    public BuildSystemPort.BuildModel resolve(Path repositoryRoot, Path evidenceSink) {
        List<Binding> bindings = bindings(repositoryRoot);
        if (bindings.isEmpty()) {
            return new BuildSystemPort.BuildModel(BuildSystemPort.Kind.UNKNOWN, null, null, false,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(new BuildSystemPort.ResolutionIssue("BLOCKING", null,
                            "No Maven or Gradle build was detected under " + repositoryRoot,
                            "Point --repo at a repository containing a pom.xml or a Gradle build.")),
                    Map.of(), false,
                    "No build system detected; there is no authoritative model to obtain (R5).");
        }

        List<BuildSystemPort.BuildModel> models = new ArrayList<>();
        for (Binding binding : bindings) {
            BuildSystemPort.BuildModel model = binding.provider().resolve(repositoryRoot, evidenceSink);
            models.add(stampModules(model, binding.kind()));
        }
        if (models.size() == 1) {
            return models.get(0);
        }
        return merge(models);
    }

    /** Marks every module of a provider's model with the provider that produced it. */
    private static BuildSystemPort.BuildModel stampModules(BuildSystemPort.BuildModel model,
                                                           BuildSystemPort.Kind kind) {
        List<BuildSystemPort.ModuleModel> stamped = new ArrayList<>();
        for (BuildSystemPort.ModuleModel module : model.modules()) {
            stamped.add(module.withBuildKind(kind));
        }
        return new BuildSystemPort.BuildModel(model.kind(), model.toolVersion(),
                model.toolInvocation(), model.wrapperUsed(), stamped, model.dependencies(),
                model.plugins(), model.managedVersions(), model.repositories(), model.issues(),
                model.toolchains(), model.authoritative(), model.degradedReason());
    }

    /**
     * Merges provider models into one composite model.
     *
     * <p>Deliberately labelled MIXED. The previous merge returned {@code Kind.MAVEN} with the first
     * model's tool version, so a repository with a Gradle half reported a Maven tool version that had
     * never resolved any of it.
     */
    private static BuildSystemPort.BuildModel merge(List<BuildSystemPort.BuildModel> models) {
        List<BuildSystemPort.ModuleModel> modules = new ArrayList<>();
        List<BuildSystemPort.ResolvedDependency> dependencies = new ArrayList<>();
        List<BuildSystemPort.ResolvedPlugin> plugins = new ArrayList<>();
        List<BuildSystemPort.ManagedVersion> managed = new ArrayList<>();
        List<BuildSystemPort.RepositoryRef> repositories = new ArrayList<>();
        List<BuildSystemPort.ResolutionIssue> issues = new ArrayList<>();
        Map<String, String> toolchains = new LinkedHashMap<>();
        List<String> invocations = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        boolean authoritative = true;
        boolean wrapper = false;
        StringBuilder degraded = new StringBuilder();

        for (BuildSystemPort.BuildModel model : models) {
            modules.addAll(model.modules());
            dependencies.addAll(model.dependencies());
            plugins.addAll(model.plugins());
            managed.addAll(model.managedVersions());
            repositories.addAll(model.repositories());
            issues.addAll(model.issues());
            model.toolchains().forEach((k, v) -> toolchains.put(model.kind() + "." + k, v));
            authoritative = authoritative && model.authoritative();
            wrapper = wrapper || model.wrapperUsed();
            if (model.toolInvocation() != null) {
                invocations.add(model.kind() + "=" + model.toolInvocation());
            }
            if (model.toolVersion() != null) {
                versions.add(model.kind() + "=" + model.toolVersion());
            }
            if (model.degradedReason() != null) {
                degraded.append(model.kind()).append(": ").append(model.degradedReason()).append(' ');
            }
        }

        return new BuildSystemPort.BuildModel(BuildSystemPort.Kind.MIXED,
                String.join("; ", versions), String.join("; ", invocations), wrapper,
                modules, dependencies, plugins, managed,
                new ArrayList<>(new LinkedHashSet<>(repositories)), issues, toolchains,
                authoritative, degraded.length() == 0 ? null : degraded.toString().trim());
    }

    private boolean gradleModulePresent(Path repositoryRoot) {
        try (var stream = java.nio.file.Files.list(repositoryRoot)) {
            return stream.filter(java.nio.file.Files::isDirectory).anyMatch(gradle::supports);
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
