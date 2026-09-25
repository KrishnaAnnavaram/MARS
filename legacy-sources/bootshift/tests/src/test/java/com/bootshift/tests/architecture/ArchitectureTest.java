package com.bootshift.tests.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.bootshift.core.policy.LicensePolicy;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the ports-and-adapters boundaries (spec section 5).
 *
 * <p>These rules exist because the layering is load-bearing, not decorative: if the core domain can
 * reach a concrete tool, the harness stops being portable and the OSS boundary stops being
 * checkable.
 */
class ArchitectureTest {

    private static JavaClasses harness;

    @BeforeAll
    static void importClasses() {
        harness = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bootshift");
    }

    @Test
    @DisplayName("core must not depend on ports, adapters, stages or the CLI")
    void coreIsIndependent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bootshift.ports..", "com.bootshift.adapters..",
                        "com.bootshift.stages..", "com.bootshift.cli..")
                .because("the core domain is the innermost layer and must stay portable");
        rule.check(harness);
    }

    @Test
    @DisplayName("core must not import concrete external tooling")
    void coreImportsNoTooling() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.eclipse.jgit..", "com.github.javaparser..",
                        "org.apache.maven..", "picocli..")
                .because("external technologies belong behind ports, never inside the domain (R8)");
        rule.check(harness);
    }

    @Test
    @DisplayName("ports must not depend on adapters, stages or the CLI")
    void portsAreInterfacesOverCore() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.ports..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bootshift.adapters..", "com.bootshift.stages..",
                        "com.bootshift.cli..")
                .because("a port that knows its adapter is not a port");
        rule.check(harness);
    }

    @Test
    @DisplayName("adapters must not depend on stages or the CLI")
    void adaptersDoNotDependOnStages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.adapters..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bootshift.stages..", "com.bootshift.cli..")
                .because("adapters implement ports and know nothing about pipeline sequencing");
        rule.check(harness);
    }

    @Test
    @DisplayName("the CLI must contain no migration semantics")
    void cliDelegates() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.bootshift.cli..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("com.bootshift..", "picocli..", "java..", "com.fasterxml..",
                        "org.slf4j..")
                .because("the CLI parses options and delegates; deleting it must not delete any "
                        + "stage logic (R24)");
        rule.check(harness);
    }

    @Test
    @DisplayName("only the mutation gateway may write application source")
    void onlyGatewayWritesSource() {
        // Transformation and repair produce proposals. Any filesystem write from those packages would
        // be a bypass of the single authorized writer (R13).
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.bootshift.stages.stage12..",
                        "com.bootshift.stages.stage13..", "com.bootshift.adapters.transform..")
                .should().callMethodWhere(new DescribedPredicate<JavaCall<?>>(
                        "a filesystem write, delete, move or copy") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (!call.getTargetOwner().getName().equals("java.nio.file.Files")) {
                            return false;
                        }
                        String method = call.getTarget().getName();
                        return method.startsWith("write") || method.startsWith("delete")
                                || method.equals("move") || method.equals("copy")
                                || method.equals("createFile");
                    }
                })
                .because("all repository mutations must flow through the FileMutationGateway (R13)");
        rule.check(harness);
    }

    @Test
    @DisplayName("no component may load a source-available Spring recipe estate")
    void noForbiddenRecipeEstate() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.openrewrite.java.spring..", "io.moderne..")
                .because("source-available Spring recipe packs are forbidden runtime dependencies "
                        + "under the strict-OSS policy (R8, R9)");
        rule.check(harness);
    }

    @Test
    @DisplayName("only the composition root may construct the run state adapter")
    void stagesUseContextForSharedPorts() {
        // StageContext and RunFactory are the composition root: wiring a concrete adapter is their
        // job. Any other stage reaching for the state adapter directly would defeat the port.
        //
        // This rule named "com.bootshift.nexus.adapters.state" - a package that does not exist in
        // this repository and never has. It matched nothing, so it passed unconditionally while
        // appearing to protect the boundary. The real package is com.bootshift.adapters.state.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.stages..")
                .and().haveSimpleNameNotEndingWith("StageContext")
                .and().haveSimpleNameNotEndingWith("RunFactory")
                .should().dependOnClassesThat()
                .resideInAPackage("com.bootshift.adapters.state..")
                .because("run state persistence is reached through StageContext so the backing store "
                        + "can be swapped without touching a stage");
        rule.check(harness);
    }

    @Test
    @DisplayName("the state adapter package the rule guards actually exists")
    void stateAdapterPackageExists() {
        // Guards the guard. A package expression that matches nothing is a rule that cannot fail,
        // and the previous one had been passing over a typo for the life of the repository.
        assertThat(harness.stream()
                .anyMatch(c -> c.getPackageName().startsWith("com.bootshift.adapters.state")))
                .as("com.bootshift.adapters.state must exist for the composition-root rule to bite")
                .isTrue();
        assertThat(harness.stream()
                .anyMatch(c -> c.getPackageName().startsWith("com.bootshift.nexus")))
                .as("com.bootshift.nexus does not exist; no rule may be written against it")
                .isFalse();
    }

    @Test
    @DisplayName("the forbidden recipe estate list has exactly one definition")
    void forbiddenRecipePolicyHasOneSourceOfTruth() {
        // LicensePolicy owns the list. The OpenRewrite provider probes against it and this test
        // asserts against it, so the three cannot drift apart into three different answers.
        assertThat(LicensePolicy.forbiddenRecipePackages())
                .contains("org.openrewrite.java.spring", "io.moderne");
        assertThat(LicensePolicy.forbiddenRecipeMarkerClasses()).isNotEmpty();
        assertThat(LicensePolicy.isForbiddenRecipeClass(
                "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0")).isTrue();
        assertThat(LicensePolicy.isForbiddenRecipeClass("org.openrewrite.java.ChangePackage"))
                .isFalse();

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift..")
                .should().dependOnClassesThat(new DescribedPredicate<JavaClass>(
                        "a forbidden source-available recipe estate") {
                    @Override
                    public boolean test(JavaClass target) {
                        return LicensePolicy.isForbiddenRecipeClass(target.getFullName());
                    }
                })
                .because("the strict-OSS profile may not load a source-available Spring recipe "
                        + "estate, and the list of what that means lives in LicensePolicy (R8, R9)");
        rule.check(harness);
    }

    @Test
    @DisplayName("untrusted process execution goes through the execution adapter")
    void processExecutionIsCentralised() {
        // Repository code - Maven plugins, Gradle scripts, annotation processors, tests - is
        // untrusted. ProcessRunner is where the allowlist, the timeout, the output cap and the
        // sanitized environment live, so a component that builds its own ProcessBuilder has none of
        // them and nothing would say so.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift..")
                .and().resideOutsideOfPackage("com.bootshift.adapters.exec..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .because("process execution is bounded by ProcessRunner; a private ProcessBuilder "
                        + "escapes the allowlist, the timeout and the output cap");
        rule.check(harness);
    }

    @Test
    @DisplayName("Runtime.exec is never called")
    void runtimeExecIsNeverCalled() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift..")
                .should().callMethodWhere(new DescribedPredicate<JavaCall<?>>(
                        "Runtime.exec") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        return call.getTargetOwner().getName().equals("java.lang.Runtime")
                                && call.getTarget().getName().equals("exec");
                    }
                })
                .because("Runtime.exec parses a command string, which is exactly the parsing step an "
                        + "argument could escape from");
        rule.check(harness);
    }

    @Test
    @DisplayName("only the mutation gateway may be the writer of application source")
    void gatewayIsTheOnlyMutationPort() {
        // MutationPort has one implementation on purpose. A second one would be a second writer,
        // and R13 is the claim that there is exactly one.
        long implementations = harness.stream()
                .filter(c -> c.getInterfaces().stream()
                        .anyMatch(i -> i.getName().equals("com.bootshift.ports.mutation.MutationPort")))
                .count();
        assertThat(implementations)
                .as("exactly one MutationPort implementation may exist (R13)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("stages reach ports through StageContext rather than constructing state adapters")
    void stagesDoNotConstructEvidenceOrStateAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift.stages..")
                .and().haveSimpleNameNotEndingWith("StageContext")
                .and().haveSimpleNameNotEndingWith("RunFactory")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bootshift.adapters.evidence..",
                        "com.bootshift.adapters.telemetry..")
                .because("the evidence store and telemetry are reached through StageContext so a "
                        + "different backend does not require touching a stage");
        rule.check(harness);
    }

    @Test
    @DisplayName("the harness must not read the application source tree as code")
    void harnessDoesNotCompileAgainstApplicationSource() {
        // ./src is input data. A harness class that imported an application type would mean the
        // harness had been compiled against the very thing it is supposed to analyse without
        // assuming anything about.
        assertThat(harness.stream()
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .map(d -> d.getTargetClass().getPackageName())
                .anyMatch(pkg -> pkg.startsWith("com.aura.vihanga")))
                .as("the harness must never depend on a type from the application under ./src")
                .isFalse();
    }

    @Test
    @DisplayName("the harness must not depend on the application under analysis")
    void noApplicationDependency() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bootshift..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.aura.vihanga..", "org.springframework..")
                .because("./src is input data; the harness core must not couple to the Spring Boot "
                        + "version being migrated");
        rule.check(harness);
    }
}
