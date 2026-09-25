package com.bootshift.ports.build;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Build tool interrogation and execution (Agent 02, Agent 13, Agent 15).
 *
 * <p>R5: the build tool itself is authoritative. Adapters must obtain the effective model by asking
 * Maven or Gradle, never by reconstructing it from XML or build-script parsing alone. Parsed
 * descriptors may only supply hints and are labelled as such.
 */
public interface BuildSystemPort {

    /**
     * Which build system produced the model.
     *
     * <p>{@link #MIXED} is not a cosmetic addition. A repository holding both a Maven reactor and a
     * Gradle build was previously labelled MAVEN, which made every downstream stage invoke Maven for
     * Gradle modules and silently produce nothing for them. A composite repository now says so.
     */
    enum Kind {
        MAVEN, GRADLE, MIXED, UNKNOWN;

        public boolean includesMaven() {
            return this == MAVEN || this == MIXED;
        }

        public boolean includesGradle() {
            return this == GRADLE || this == MIXED;
        }
    }

    /** One resolved dependency with the provenance the spec requires. */
    record ResolvedDependency(String groupId, String artifactId, String version, String type,
                              String scope, String module, String checksum, String repository,
                              boolean direct, boolean managed, String bomSource, String resolutionStatus) {
        public String ga() {
            return groupId + ":" + artifactId;
        }

        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    record ResolvedPlugin(String groupId, String artifactId, String version, String module, String phase) {
    }

    record ManagedVersion(String groupId, String artifactId, String version, String source) {
    }

    record RepositoryRef(String id, String url, boolean snapshots, boolean releases) {
    }

    /**
     * One module of the build. {@code classpath} holds the resolved compile-plus-test classpath as
     * reported by the build tool; it is what lets the code model resolve types coming from
     * dependencies instead of degrading to UNRESOLVED attribution.
     */
    record ModuleModel(String moduleId, String path, String groupId, String artifactId, String version,
                       String packaging, String parentGav, String javaVersion,
                       Map<String, String> properties, List<String> activeProfiles,
                       List<String> classpath, Kind buildKind) {

        public ModuleModel(String moduleId, String path, String groupId, String artifactId,
                           String version, String packaging, String parentGav, String javaVersion,
                           Map<String, String> properties, List<String> activeProfiles) {
            this(moduleId, path, groupId, artifactId, version, packaging, parentGav, javaVersion,
                    properties, activeProfiles, List.of(), Kind.MAVEN);
        }

        public ModuleModel(String moduleId, String path, String groupId, String artifactId,
                           String version, String packaging, String parentGav, String javaVersion,
                           Map<String, String> properties, List<String> activeProfiles,
                           List<String> classpath) {
            this(moduleId, path, groupId, artifactId, version, packaging, parentGav, javaVersion,
                    properties, activeProfiles, classpath, Kind.MAVEN);
        }

        public ModuleModel withClasspath(List<String> resolved) {
            return new ModuleModel(moduleId, path, groupId, artifactId, version, packaging, parentGav,
                    javaVersion, properties, activeProfiles, resolved, buildKind);
        }

        public ModuleModel withBuildKind(Kind kind) {
            return new ModuleModel(moduleId, path, groupId, artifactId, version, packaging, parentGav,
                    javaVersion, properties, activeProfiles, classpath, kind);
        }

        /**
         * The effective Java release for this module, as the module itself declares it.
         *
         * <p>Analysis previously parsed every repository at Java 21 regardless of what the module
         * said. That is not a harmless default: a construct the module's real level does not allow
         * parses anyway, and a construct removed after that level is silently accepted.
         */
        public int effectiveJavaRelease(int fallback) {
            String declared = javaVersion;
            if (declared == null || declared.isBlank()) {
                for (String key : List.of("maven.compiler.release", "maven.compiler.source",
                        "java.version", "sourceCompatibility")) {
                    String candidate = properties == null ? null : properties.get(key);
                    if (candidate != null && !candidate.isBlank()) {
                        declared = candidate;
                        break;
                    }
                }
            }
            if (declared == null || declared.isBlank()) {
                return fallback;
            }
            String cleaned = declared.trim();
            if (cleaned.startsWith("1.")) {
                cleaned = cleaned.substring(2);
            }
            StringBuilder digits = new StringBuilder();
            for (char c : cleaned.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            if (digits.length() == 0) {
                return fallback;
            }
            try {
                int value = Integer.parseInt(digits.toString());
                return value >= 1 && value <= 99 ? value : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    record ResolutionIssue(String severity, String module, String detail, String remediation) {
    }

    /** The complete authoritative build model for one repository. */
    record BuildModel(Kind kind, String toolVersion, String toolInvocation, boolean wrapperUsed,
                      List<ModuleModel> modules, List<ResolvedDependency> dependencies,
                      List<ResolvedPlugin> plugins, List<ManagedVersion> managedVersions,
                      List<RepositoryRef> repositories, List<ResolutionIssue> issues,
                      Map<String, String> toolchains, boolean authoritative, String degradedReason) {
    }

    /** Outcome of an actual build or test execution. */
    record ExecutionResult(boolean success, int exitCode, Duration duration, String command,
                           List<String> stdoutTail, List<String> stderrTail, Path workingDirectory) {
    }

    Kind detect(Path repositoryRoot);

    boolean supports(Path repositoryRoot);

    /** Interrogates the build tool for its effective model. Never mutates the repository. */
    BuildModel resolve(Path repositoryRoot, Path evidenceSink);

    ExecutionResult compile(Path repositoryRoot, Path modulePath, Map<String, String> options);

    ExecutionResult test(Path repositoryRoot, Path modulePath, Map<String, String> options);

    /** Runs an arbitrary allowlisted goal, e.g. coverage report generation. */
    ExecutionResult invoke(Path repositoryRoot, List<String> goals, Map<String, String> options);
}
