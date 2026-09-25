package com.mars.harness.kernel.ports.build;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The project's declared and resolved build model.
 *
 * <p>{@code authoritative} is true only when the build tool itself resolved the model (Bootshift
 * R5). A descriptor-only model is marked non-authoritative and lowers evidence confidence. It is
 * never presented as resolved.
 */
public record BuildModelView(String buildSystem, boolean authoritative, String degradedReason,
                             List<ModuleBuild> modules) {

    public BuildModelView {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public record Coordinate(String groupId, String artifactId, String version) {
        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    public record Dependency(String groupId, String artifactId, String declaredVersion, String resolvedVersion,
                             String scope, boolean managed, int line) {
        public String ga() {
            return groupId + ":" + artifactId;
        }

        public String effectiveVersion() {
            return resolvedVersion != null ? resolvedVersion : declaredVersion;
        }
    }

    /**
     * @param javaVersionSource which declaration the Java level came from, following the
     *                          reference workflow's order: java.version, maven.compiler.release,
     *                          maven.compiler.source, then compiler plugin release or source
     */
    public record ModuleBuild(String name, String path, String buildFile, Coordinate parent,
                              Map<String, String> properties, String javaVersion, String javaVersionSource,
                              List<Dependency> dependencies, List<Coordinate> plugins,
                              Map<String, String> compilerConfiguration) {
        public ModuleBuild {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
            compilerConfiguration = compilerConfiguration == null ? Map.of() : Map.copyOf(compilerConfiguration);
        }

        public Optional<Dependency> dependency(String groupId, String artifactId) {
            return dependencies.stream().filter(d -> d.groupId().equals(groupId) && d.artifactId().equals(artifactId))
                    .findFirst();
        }
    }

    public List<Dependency> allDependencies() {
        return modules.stream().flatMap(m -> m.dependencies().stream()).toList();
    }
}
