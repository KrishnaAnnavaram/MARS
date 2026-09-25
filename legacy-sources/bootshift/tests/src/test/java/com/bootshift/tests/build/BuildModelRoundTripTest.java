package com.bootshift.tests.build;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build model must survive the artifact plane intact.
 *
 * <p>Stage 02 resolves the authoritative model once, and every later stage needs it again. Each one
 * used to rebuild a partial copy by hand from whichever artifacts it happened to read, and those
 * copies dropped managed versions, plugins, repositories, resolution issues and toolchain details on
 * the floor. A stage that then asked "which BOM manages this coordinate?" got an empty list and
 * concluded that nothing did.
 */
class BuildModelRoundTripTest {

    private static BuildSystemPort.BuildModel richModel() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("java.version", "17");
        properties.put("spring-cloud.version", "2021.0.7");

        BuildSystemPort.ModuleModel maven = new BuildSystemPort.ModuleModel(
                "employee-service", "/repo/employee-service", "com.example", "employee-service",
                "0.0.1-SNAPSHOT", "war", "org.springframework.boot:spring-boot-starter-parent:2.7.12",
                "17", properties, List.of("unitTest"),
                List.of("/m2/a.jar", "/m2/b.jar"), BuildSystemPort.Kind.MAVEN);
        BuildSystemPort.ModuleModel gradle = new BuildSystemPort.ModuleModel(
                "reporting", "/repo/reporting", "com.example", "reporting", "1.0", "jar", null,
                "21", Map.of("sourceCompatibility", "21"), List.of(), List.of(),
                BuildSystemPort.Kind.GRADLE);

        return new BuildSystemPort.BuildModel(
                BuildSystemPort.Kind.MIXED, "MAVEN=3.9.6; GRADLE=8.7", "mvn -B; gradlew", true,
                List.of(maven, gradle),
                List.of(new BuildSystemPort.ResolvedDependency("org.springframework.boot",
                                "spring-boot-starter-web", "2.7.12", "jar", "compile",
                                "employee-service", "sha256:abc", "central", true, true,
                                "spring-boot-dependencies", "RESOLVED"),
                        new BuildSystemPort.ResolvedDependency("org.projectlombok", "lombok",
                                "1.18.24", "jar", "provided", "employee-service", null, "central",
                                true, true, null, "RESOLVED")),
                List.of(new BuildSystemPort.ResolvedPlugin("org.springframework.boot",
                        "spring-boot-maven-plugin", "2.7.12", "employee-service", "package")),
                List.of(new BuildSystemPort.ManagedVersion("org.springframework.cloud",
                        "spring-cloud-dependencies", "2021.0.7", "imported-bom")),
                List.of(new BuildSystemPort.RepositoryRef("central",
                        "https://repo1.maven.org/maven2", false, true)),
                List.of(new BuildSystemPort.ResolutionIssue("WARNING", "employee-service",
                        "resolve-plugins failed", "check network access")),
                Map.of("java.version", "21", "java.vendor", "Microsoft"),
                true, null);
    }

    @Test
    @DisplayName("encode then decode preserves every part of the model")
    void roundTripIsSemanticallyEqual() {
        BuildSystemPort.BuildModel original = richModel();
        ObjectNode encoded = BuildModelCodec.encode(original);
        BuildSystemPort.BuildModel decoded = BuildModelCodec.decode(encoded, null);

        assertThat(decoded.kind()).isEqualTo(BuildSystemPort.Kind.MIXED);
        assertThat(decoded.toolVersion()).isEqualTo(original.toolVersion());
        assertThat(decoded.toolInvocation()).isEqualTo(original.toolInvocation());
        assertThat(decoded.wrapperUsed()).isTrue();
        assertThat(decoded.authoritative()).isTrue();

        // The four collections that the hand-written rehydration silently dropped.
        assertThat(decoded.managedVersions()).hasSize(1);
        assertThat(decoded.managedVersions().get(0).artifactId())
                .isEqualTo("spring-cloud-dependencies");
        assertThat(decoded.plugins()).hasSize(1);
        assertThat(decoded.plugins().get(0).artifactId()).isEqualTo("spring-boot-maven-plugin");
        assertThat(decoded.repositories()).hasSize(1);
        assertThat(decoded.repositories().get(0).url()).isEqualTo("https://repo1.maven.org/maven2");
        assertThat(decoded.issues()).hasSize(1);
        assertThat(decoded.issues().get(0).severity()).isEqualTo("WARNING");
        assertThat(decoded.toolchains()).containsEntry("java.vendor", "Microsoft");

        assertThat(decoded.modules()).hasSize(2);
        BuildSystemPort.ModuleModel decodedMaven = decoded.modules().get(0);
        assertThat(decodedMaven.classpath()).containsExactly("/m2/a.jar", "/m2/b.jar");
        assertThat(decodedMaven.properties()).containsEntry("spring-cloud.version", "2021.0.7");
        assertThat(decodedMaven.activeProfiles()).containsExactly("unitTest");
        assertThat(decodedMaven.buildKind()).isEqualTo(BuildSystemPort.Kind.MAVEN);
        assertThat(decoded.modules().get(1).buildKind()).isEqualTo(BuildSystemPort.Kind.GRADLE);

        assertThat(decoded.dependencies()).hasSize(2);
        assertThat(decoded.dependencies().get(0).bomSource()).isEqualTo("spring-boot-dependencies");
        assertThat(decoded.dependencies().get(0).checksum()).isEqualTo("sha256:abc");
    }

    @Test
    @DisplayName("the fingerprint is stable across a round trip and changes when the model does")
    void fingerprintIsStableAndSensitive() {
        BuildSystemPort.BuildModel original = richModel();
        String before = BuildModelCodec.fingerprint(original);
        String after = BuildModelCodec.fingerprint(
                BuildModelCodec.decode(BuildModelCodec.encode(original), null));
        assertThat(after).isEqualTo(before);

        BuildSystemPort.BuildModel degraded = new BuildSystemPort.BuildModel(original.kind(),
                original.toolVersion(), original.toolInvocation(), original.wrapperUsed(),
                original.modules(), original.dependencies(), original.plugins(),
                List.of(), original.repositories(), original.issues(), original.toolchains(),
                original.authoritative(), original.degradedReason());
        assertThat(BuildModelCodec.fingerprint(degraded)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a composite repository is MIXED, not silently labelled MAVEN")
    void mixedIsRepresentedExplicitly() {
        assertThat(BuildSystemPort.Kind.MIXED.includesMaven()).isTrue();
        assertThat(BuildSystemPort.Kind.MIXED.includesGradle()).isTrue();
        assertThat(BuildSystemPort.Kind.MAVEN.includesGradle()).isFalse();
        assertThat(BuildSystemPort.Kind.GRADLE.includesMaven()).isFalse();
    }

    @Test
    @DisplayName("a module's effective Java release comes from the module, not from a fixed default")
    void effectiveJavaReleaseIsPerModule() {
        BuildSystemPort.ModuleModel declared = new BuildSystemPort.ModuleModel("a", "/a", "g", "a",
                "1", "jar", null, "17", Map.of(), List.of());
        assertThat(declared.effectiveJavaRelease(21)).isEqualTo(17);

        BuildSystemPort.ModuleModel viaProperty = new BuildSystemPort.ModuleModel("b", "/b", "g", "b",
                "1", "jar", null, null, Map.of("maven.compiler.release", "11"), List.of());
        assertThat(viaProperty.effectiveJavaRelease(21)).isEqualTo(11);

        BuildSystemPort.ModuleModel legacy = new BuildSystemPort.ModuleModel("c", "/c", "g", "c",
                "1", "jar", null, "1.8", Map.of(), List.of());
        assertThat(legacy.effectiveJavaRelease(21)).isEqualTo(8);

        BuildSystemPort.ModuleModel unknown = new BuildSystemPort.ModuleModel("d", "/d", "g", "d",
                "1", "jar", null, null, Map.of(), List.of());
        assertThat(unknown.effectiveJavaRelease(21)).isEqualTo(21);
    }
}
