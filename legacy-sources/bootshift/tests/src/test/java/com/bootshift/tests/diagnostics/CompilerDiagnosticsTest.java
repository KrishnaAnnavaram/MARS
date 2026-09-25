package com.bootshift.tests.diagnostics;

import com.bootshift.stages.stage13.CompilerDiagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root-cause clustering tests.
 *
 * <p>Clustering is what stops the repair loop spending its whole budget on symptoms, so these tests
 * attack the two ways it silently degrades: losing the symbol name that identifies the cause, and
 * mistaking Maven's aggregate wrapper line for a toolchain fault.
 */
class CompilerDiagnosticsTest {

    private static final List<String> JAVAC_MULTILINE = List.of(
            "[ERROR] /C:/ws/mod/src/main/java/App.java:[6,54] cannot find symbol",
            "[ERROR]   symbol:   class EnableEurekaClient",
            "[ERROR]   location: package org.springframework.cloud.netflix.eureka");

    @Test
    @DisplayName("javac continuation lines fold into the diagnostic they belong to")
    void continuationLinesFold() {
        List<CompilerDiagnostics.Diagnostic> parsed = CompilerDiagnostics.parse(JAVAC_MULTILINE);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).line()).isEqualTo(6);
        assertThat(parsed.get(0).message())
                .contains("cannot find symbol")
                .contains("symbol:   class EnableEurekaClient")
                .contains("location: package org.springframework.cloud.netflix.eureka");
    }

    @Test
    @DisplayName("the symbol name reaches the cluster, so removed APIs are distinguishable")
    void symbolNameReachesTheCluster() {
        List<CompilerDiagnostics.Cluster> clusters =
                CompilerDiagnostics.cluster(CompilerDiagnostics.parse(JAVAC_MULTILINE));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).rootCause())
                .isEqualTo(CompilerDiagnostics.RootCause.REMOVED_OR_RENAMED_API);
        assertThat(clusters.get(0).signature()).isEqualTo("removed-api:EnableEurekaClient");
        assertThat(clusters.get(0).explanation()).contains("EnableEurekaClient");
        // "unknown" is what the parser produced before continuation lines were folded in, and it
        // collapsed every removed API in the run into one useless bucket.
        assertThat(clusters.get(0).explanation()).doesNotContain("Symbol unknown");
    }

    @Test
    @DisplayName("Maven's framing is not recorded as a diagnostic at all")
    void mavenFramingIsNotADiagnostic() {
        // Two separate defects met on these lines. Classifying the wrapper as PLUGIN_OR_TOOLCHAIN
        // told the repair loop that no source edit could help, ending repair after one round on an
        // edge that was entirely repairable. Classifying the rest as APPLICATION_SPECIFIC - "no
        // framework-level cause explains this" - turned a residual of 16 real errors into 32, and
        // the repair budget is sized against that number.
        //
        // None of these lines is a diagnostic. The wrapper summarizes the per-file errors printed
        // above it, and the others are banners and help pointers.
        List<CompilerDiagnostics.Diagnostic> parsed = CompilerDiagnostics.parse(List.of(
                "[ERROR] COMPILATION ERROR : ",
                "[ERROR] -------------------------------------------------------------",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-compiler-plugin:3.10.1:compile (default-compile) on project "
                        + "employee-service: Compilation failure: Compilation failure: ",
                "[ERROR] -> [Help 1]",
                "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/"
                        + "MojoFailureException"));

        assertThat(parsed).isEmpty();
        assertThat(CompilerDiagnostics.cluster(parsed)).isEmpty();
    }

    @Test
    @DisplayName("framing around real errors leaves exactly the real errors")
    void framingDoesNotDisplaceRealDiagnostics() {
        List<CompilerDiagnostics.Diagnostic> parsed = CompilerDiagnostics.parse(List.of(
                "[ERROR] COMPILATION ERROR : ",
                "[ERROR] /C:/ws/mod/src/main/java/App.java:[6,54] cannot find symbol",
                "[ERROR]   symbol:   class EnableEurekaClient",
                "[ERROR]   location: package org.springframework.cloud.netflix.eureka",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-compiler-plugin:3.10.1:compile (default-compile) on project a: "
                        + "Compilation failure: Compilation failure: ",
                "[ERROR] -> [Help 1]"));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).message()).contains("EnableEurekaClient");

        List<CompilerDiagnostics.Cluster> clusters = CompilerDiagnostics.cluster(parsed);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).rootCause())
                .isEqualTo(CompilerDiagnostics.RootCause.REMOVED_OR_RENAMED_API);
        assertThat(CompilerDiagnostics.isEnvironmental(clusters.get(0).rootCause())).isFalse();
    }

    @Test
    @DisplayName("a real toolchain fault is still environmental")
    void realToolchainFaultIsEnvironmental() {
        List<CompilerDiagnostics.Cluster> clusters = CompilerDiagnostics.cluster(
                CompilerDiagnostics.parse(List.of(
                        "[ERROR] Fatal error compiling: java.lang.NoSuchFieldError: "
                                + "com.sun.tools.javac.tree.JCTree$JCImport.qualid")));

        assertThat(clusters.get(0).rootCause())
                .isEqualTo(CompilerDiagnostics.RootCause.PLUGIN_OR_TOOLCHAIN);
        assertThat(CompilerDiagnostics.isEnvironmental(clusters.get(0).rootCause())).isTrue();
    }

    @Test
    @DisplayName("Windows paths are normalized so a diagnostic can be joined to a FILE_ID")
    void windowsPathsAreNormalized() {
        List<CompilerDiagnostics.Diagnostic> parsed = CompilerDiagnostics.parse(List.of(
                "[ERROR] /C:/ws/mod/src/main/java/App.java:[6,54] cannot find symbol"));

        assertThat(parsed.get(0).file()).isEqualTo("C:/ws/mod/src/main/java/App.java");
    }

    @Test
    @DisplayName("an unresolved dependency owns every symbol error it caused")
    void resolutionFailureOwnsItsSymptoms() {
        List<CompilerDiagnostics.Cluster> clusters = CompilerDiagnostics.cluster(
                CompilerDiagnostics.parse(List.of(
                        "[ERROR] Could not resolve dependencies for project a:b:jar:1.0",
                        "[ERROR] /C:/ws/A.java:[3,8] package com.example does not exist",
                        "[ERROR] /C:/ws/B.java:[9,1] cannot find symbol")));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).rootCause())
                .isEqualTo(CompilerDiagnostics.RootCause.DEPENDENCY_RESOLUTION);
        assertThat(clusters.get(0).size()).isEqualTo(3);
    }
}
