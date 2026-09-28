package com.mars.harness.tests.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The Control Center is an observation and decision interface, never a second engine. These rules
 * hold it to that on the compiled code, in addition to every rule of {@link ArchitectureTest}
 * (which already applies to it: it writes no files, records no approval except through
 * HarnessEngine, and drives no mutation writer).
 */
class ControlCenterArchitectureTest {

    private static final String CC = "com.mars.harness.controlcenter..";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mars.harness", "com.bootshift");
    }

    @Test
    void theControlCenterCannotReachTheMutationMachinery() {
        noClasses().that().resideInAPackage(CC)
                .should().dependOnClassesThat().resideInAnyPackage("com.mars.harness.kernel.engine.mutation..",
                        "com.bootshift.adapters.mutation..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.mars.harness.kernel.ports.mutation.ProposalSink")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.mars.harness.kernel.ports.mutation.MutationPort")
                .because("only the engine applies change, through the Mutation Gateway, after its own checks")
                .check(classes);
    }

    @Test
    void theControlCenterNeverWritesRunStateEvidenceOrEvents() {
        noClasses().that().resideInAPackage(CC)
                .should().callMethodWhere(DescribedPredicate.describe("a state- or evidence-writing kernel call",
                        (JavaCall<?> call) -> {
                            String owner = call.getTargetOwner().getName();
                            String name = call.getName();
                            return name.equals("transition") && owner.equals("com.mars.harness.kernel.core.run.RunStateMachine")
                                    || owner.equals("com.mars.harness.kernel.engine.run.RunSession")
                                    && (name.startsWith("save") || name.equals("note") || name.equals("load")
                                    || name.equals("create"))
                                    || owner.endsWith("ExecutionEventStore") && name.equals("append")
                                    || owner.equals("com.mars.harness.kernel.engine.event.ExecutionEventRecorder")
                                    || (owner.endsWith("EvidenceStore") || owner.endsWith("EvidenceLog"))
                                    && (name.equals("record") || name.equals("append"))
                                    || owner.endsWith("ArtifactStore") && (name.startsWith("write") || name.equals("importFile"))
                                    || owner.endsWith("ProposalStore") && name.equals("save")
                                    || owner.endsWith("CheckpointStore") && name.equals("save")
                                    || owner.endsWith("LineageLedger") && name.equals("append")
                                    || owner.equals("com.bootshift.core.ledger.ChangeLedger") && name.equals("append");
                        }))
                .because("the persisted run is the state; the Control Center reads it and changes it only by asking the "
                        + "engine to analyze, decide or resume")
                .check(classes);
    }

    @Test
    void theControlCenterCannotConstructDecisionsOrProposals() {
        noClasses().that().resideInAPackage(CC)
                .should().callConstructorWhere(DescribedPredicate.<JavaConstructorCall>describe(
                        "new Decision / new ChangeProposal", c -> Set.of("com.mars.harness.kernel.core.decision.Decision",
                                "com.mars.harness.kernel.core.change.ChangeProposal").contains(c.getTargetOwner().getName())))
                .because("decisions are built and recorded by HarnessEngine's decide* entry points only")
                .check(classes);
    }

    @Test
    void onlyTheCommandServiceDrivesTheEngine() {
        noClasses().that().resideInAPackage(CC)
                .and().doNotHaveFullyQualifiedName("com.mars.harness.controlcenter.run.RunCommandService")
                .should().callMethodWhere(DescribedPredicate.describe("HarnessEngine analyze/decide*/resume/submit*/apply*",
                        (JavaCall<?> call) -> call.getTargetOwner().getName().equals("com.mars.harness.kernel.engine.HarnessEngine")
                                && (call.getName().startsWith("decide") || call.getName().equals("analyze")
                                || call.getName().equals("resume") || call.getName().startsWith("submit")
                                || call.getName().startsWith("apply"))))
                .because("every state-changing command is in one reviewed place, behind the per-run permit")
                .check(classes);
        noClasses().that().resideInAPackage(CC)
                .should().callMethodWhere(DescribedPredicate.describe("apply to the original project",
                        (JavaCall<?> call) -> call.getTargetOwner().getName().equals("com.mars.harness.kernel.engine.HarnessEngine")
                                && (call.getName().equals("applyToProject") || call.getName().equals("decideApply"))))
                .because("writing the result into the original project stays a CLI-only, explicitly decided step")
                .check(classes);
    }

    @Test
    void transportConcernsDoNotLeakIntoTheKernelOrTheCapabilities() {
        noClasses().that().resideInAnyPackage("com.mars.harness.kernel..", "com.mars.harness.capabilities..",
                        "com.mars.harness.cli..", "com.bootshift..")
                .should().dependOnClassesThat().resideInAnyPackage(CC, "org.springframework..", "jakarta.servlet..")
                .because("the web layer is one more composition root over an engine that knows nothing about it")
                .check(classes);
    }

    @Test
    void capabilitiesGainNoApprovalOrEventWritingAuthority() {
        noClasses().that().resideInAPackage("com.mars.harness.capabilities..")
                .should().dependOnClassesThat().resideInAnyPackage("com.mars.harness.kernel.ports.approval..", CC)
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.mars.harness.kernel.ports.event.ExecutionEventStore")
                .check(classes);
    }
}
