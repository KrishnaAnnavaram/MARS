package com.mars.harness.tests.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Spec §32.2. The boundaries that keep the harness safe are enforced on the compiled code, not
 * only documented:
 *
 * <ul>
 *   <li>no direct source writes outside the Mutation Gateway</li>
 *   <li>core does not depend on concrete adapters</li>
 *   <li>capability packs do not own global state</li>
 *   <li>nothing but the human-decision entry points can record an approval (so no agent, model or
 *       capability can authorize a change)</li>
 *   <li>reports are not treated as authoritative state</li>
 *   <li>protected layer boundaries remain intact (Bootshift does not know the unified kernel)</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mars.harness", "com.bootshift");
    }

    private static final Set<String> FILE_WRITE_METHODS = Set.of("write", "writeString", "newOutputStream",
            "newBufferedWriter", "copy", "move", "delete", "deleteIfExists", "createFile", "createTempFile");

    private static DescribedPredicate<JavaCall<?>> fileWrite() {
        return DescribedPredicate.describe("a java.nio.file.Files write/copy/move/delete", call ->
                call.getTargetOwner().isEquivalentTo(java.nio.file.Files.class)
                        && FILE_WRITE_METHODS.contains(call.getName()));
    }

    @Test
    void capabilityPacksNeverWriteFilesThemselves() {
        ArchRule rule = noClasses().that().resideInAPackage("com.mars.harness.capabilities..")
                .should().callMethodWhere(fileWrite())
                .orShould().callConstructor(java.io.FileOutputStream.class, String.class)
                .orShould().callConstructor(java.io.FileOutputStream.class, java.io.File.class)
                .orShould().callConstructor(java.io.FileWriter.class, String.class)
                .orShould().callConstructor(java.io.FileWriter.class, java.io.File.class)
                .because("a capability publishes artifacts through ArtifactStore and changes code only by proposing to "
                        + "the Mutation Gateway");
        rule.check(classes);
    }

    @Test
    void onlyNamedKernelComponentsWriteFilesAndNoneOfThemTargetsTrackedSource() {
        ArchRule rule = noClasses().that().resideInAPackage("com.mars.harness..")
                .and().doNotBelongToAnyOf(com.mars.harness.kernel.adapters.store.FilesystemArtifactStore.class,
                        com.mars.harness.kernel.adapters.store.FilesystemApprovalStore.class,
                        com.mars.harness.kernel.adapters.excel.Xlsx.class,
                        com.mars.harness.kernel.core.evidence.EvidenceLog.class,
                        com.mars.harness.kernel.engine.exec.WorkspaceSandbox.class,
                        com.mars.harness.kernel.engine.ledger.LineageLedger.class,
                        com.mars.harness.kernel.engine.proposal.ProposalStore.class,
                        com.mars.harness.kernel.engine.mutation.ProjectApplier.class)
                .should().callMethodWhere(fileWrite())
                .because("every writer is named: run-area stores and ledgers, the disposable exec sandbox, and the "
                        + "decision-bound apply-to-project. Tracked workspace source is written only by Bootshift's "
                        + "FileMutationGateway, behind the kernel MutationGateway");
        rule.check(classes);
    }

    @Test
    void theWriteRuleCatchesADirectTrackedSourceWrite() {
        // spec §32.15: the static half of bypass detection must actually fail on a bypass
        JavaClasses rogue = new ClassFileImporter().importClasses(com.mars.harness.tests.architecture.rogue.RogueWriter.class);
        ArchRule rule = noClasses().should().callMethodWhere(fileWrite());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rule.check(rogue)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("RogueWriter.patch");
    }

    @Test
    void onlyTheKernelMutationGatewayDrivesBootshiftsWriter() {
        noClasses().that().resideOutsideOfPackage("com.mars.harness.kernel.engine.mutation..")
                .and().resideInAPackage("com.mars.harness..")
                .and().doNotHaveFullyQualifiedName("com.mars.harness.kernel.adapters.bootshift.BootshiftBridge")
                .should().callMethodWhere(DescribedPredicate.describe("FileMutationGateway.apply/revertTo or BootshiftBridge.gateway",
                        call -> call.getTargetOwner().getName().equals("com.bootshift.adapters.mutation.FileMutationGateway")
                                && Set.of("apply", "revertTo", "checkpoint").contains(call.getName())
                                || call.getTargetOwner().getName().equals("com.mars.harness.kernel.adapters.bootshift.BootshiftBridge")
                                && call.getName().equals("gateway")))
                .because("there is one Mutation Gateway")
                .check(classes);
    }

    @Test
    void coreAndPortsDoNotDependOnConcreteAdaptersEngineOrCapabilities() {
        noClasses().that().resideInAPackage("com.mars.harness.kernel.core..")
                .should().dependOnClassesThat().resideInAnyPackage("com.mars.harness.kernel.adapters..",
                        "com.mars.harness.kernel.engine..", "com.mars.harness.kernel.ports..", "com.mars.harness.capabilities..",
                        "com.mars.harness.cli..", "com.bootshift.adapters..", "com.bootshift.stages..")
                .check(classes);
        noClasses().that().resideInAPackage("com.mars.harness.kernel.ports..")
                .should().dependOnClassesThat().resideInAnyPackage("com.mars.harness.kernel.adapters..",
                        "com.mars.harness.kernel.engine..", "com.mars.harness.capabilities..", "com.mars.harness.cli..",
                        "com.bootshift.adapters..", "com.bootshift.stages..")
                .check(classes);
    }

    @Test
    void capabilityPacksSeeOnlyTheKernelContracts() {
        noClasses().that().resideInAPackage("com.mars.harness.capabilities..")
                .should().dependOnClassesThat().resideInAnyPackage("com.mars.harness.kernel.adapters..",
                        "com.mars.harness.kernel.engine..", "com.mars.harness.cli..", "com.bootshift.adapters..",
                        "com.bootshift.stages..")
                .because("a capability is a pack behind the common contract, replaceable without touching the kernel")
                .check(classes);
        noClasses().that().resideInAPackage("com.mars.harness.capabilities.migration..")
                .should().dependOnClassesThat().resideInAPackage("com.mars.harness.capabilities.security..")
                .check(classes);
        noClasses().that().resideInAPackage("com.mars.harness.capabilities.security..")
                .should().dependOnClassesThat().resideInAPackage("com.mars.harness.capabilities.migration..")
                .check(classes);
        // the engine is wired with capabilities by the composition root only
        noClasses().that().resideInAPackage("com.mars.harness.kernel..")
                .should().dependOnClassesThat().resideInAPackage("com.mars.harness.capabilities..")
                .check(classes);
    }

    @Test
    void capabilityPacksDoNotOwnGlobalMutableState() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.mars.harness.capabilities..")
                .and().areStatic()
                .should().beFinal()
                .because("per-run state lives in the kernel's run session, never in a capability singleton")
                .check(classes);
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.mars.harness.kernel.engine..")
                .and().areStatic().and().doNotHaveModifier(JavaModifier.SYNTHETIC)
                .should().beFinal()
                .check(classes);
    }

    @Test
    void onlyHumanDecisionEntryPointsCanRecordADecision() {
        noClasses().that().resideInAPackage("com.mars.harness..")
                .and().doNotHaveFullyQualifiedName("com.mars.harness.kernel.engine.HarnessEngine")
                .should().callMethodWhere(DescribedPredicate.describe("ApprovalPort.record", call ->
                        call.getName().equals("record") && (call.getTargetOwner().getName()
                                .equals("com.mars.harness.kernel.ports.approval.ApprovalPort") || call.getTargetOwner().getName()
                                .equals("com.mars.harness.kernel.adapters.store.FilesystemApprovalStore"))))
                .because("the harness, its capabilities and any model may propose, but only a recorded human decision "
                        + "authorizes; decisions enter only through the decide* commands")
                .check(classes);
        noClasses().that().resideInAPackage("com.mars.harness.capabilities..")
                .should().dependOnClassesThat().resideInAPackage("com.mars.harness.kernel.ports.approval..")
                .orShould().callConstructorWhere(DescribedPredicate.<JavaConstructorCall>describe("Decision constructor", c ->
                        c.getTargetOwner().getName().equals("com.mars.harness.kernel.core.decision.Decision")))
                .check(classes);
    }

    @Test
    void reportsAreRenderedFromStateAndNeverWriteState() {
        noClasses().that().resideInAPackage("com.mars.harness.kernel.engine.report..")
                .should().callMethodWhere(DescribedPredicate.describe("a state-changing kernel call", call ->
                        call.getTargetOwner().getName().startsWith("com.mars.harness.kernel.engine.mutation.")
                                || call.getName().equals("record") && call.getTargetOwner().getName().contains("Approval")
                                || call.getName().startsWith("save") && call.getTargetOwner().getName()
                                .equals("com.mars.harness.kernel.engine.run.RunSession")
                                || call.getName().equals("transition")))
                .because("a report is a view of the run; the run record, ledgers and registries are the authority")
                .check(classes);
    }

    @Test
    void processesAreLaunchedOnlyByAdapters() {
        noClasses().that().resideInAPackage("com.mars.harness..")
                .and().resideOutsideOfPackage("com.mars.harness.kernel.adapters..")
                .should().callConstructorWhere(DescribedPredicate.<JavaConstructorCall>describe("new ProcessBuilder", c ->
                        c.getTargetOwner().isEquivalentTo(ProcessBuilder.class)))
                .orShould().callMethodWhere(DescribedPredicate.describe("Runtime.exec or Bootshift ProcessRunner", call ->
                        call.getTargetOwner().isEquivalentTo(Runtime.class) && call.getName().equals("exec")
                                || call.getTargetOwner().getName().equals("com.bootshift.adapters.exec.ProcessRunner")))
                .because("protection map A9: every build and runtime process goes through the adapters (allowlist, "
                        + "timeout, redaction)")
                .check(classes);
    }

    @Test
    void protectedLayerBoundariesRemainIntact() {
        noClasses().that().resideInAPackage("com.bootshift..")
                .should().dependOnClassesThat().resideInAPackage("com.mars..")
                .because("Bootshift is preserved unchanged as the kernel base (ADR-U001); it does not know the harness")
                .check(classes);
        // Bootshift's internal layering (core <- ports <- adapters <- stages) is still respected
        noClasses().that().resideInAPackage("com.bootshift.core..")
                .should().dependOnClassesThat().resideInAnyPackage("com.bootshift.adapters..", "com.bootshift.stages..")
                .check(classes);
    }
}
