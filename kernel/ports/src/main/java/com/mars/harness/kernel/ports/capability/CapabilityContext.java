package com.mars.harness.kernel.ports.capability;

import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.graph.CanonicalGraph;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.ports.analysis.CodeModelPort;
import com.mars.harness.kernel.ports.build.BuildModelView;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.event.ActivityReporter;
import com.mars.harness.kernel.ports.evidence.ArtifactStore;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;
import com.mars.harness.kernel.ports.execution.ExecutionSandbox;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Everything a capability pack may reach, and nothing more.
 *
 * <p>Capabilities own no global state (spec §32.2). Identity, graph, findings, evidence, decisions
 * and the run state belong to the kernel, which exposes them here read-only. The only outputs a
 * capability has are its return values, artifacts published through {@link #artifacts()}, evidence
 * through {@link #evidence()}, and proposals through a {@code ProposalSink} handed to it when it
 * is authorised to execute.
 */
public interface CapabilityContext {

    String runId();

    UnifiedPolicy policy();

    /** Root of the harness installation: schemas, policies, knowledge and legacy data files. */
    Path harnessRoot();

    /** Reads a tracked workspace file (current state). Read-only by contract. */
    Optional<String> readWorkspaceFile(String relativePath);

    /** Reads a file from the immutable pre-mutation snapshot. */
    Optional<String> readSnapshotFile(String relativePath);

    IdentityView identity();

    CanonicalGraph graph();

    BuildModelView buildModel();

    List<Finding> findings();

    /** The finding-source files the developer supplied (copied into the run), e.g. advisory feeds. */
    List<Path> findingInputs();

    EvidenceStore evidence();

    ArtifactStore artifacts();

    CodeModelPort codeModel();

    BuildPort build();

    RuntimePort runtime();

    LifecyclePort lifecycle();

    ExecutionSandbox sandbox();

    /** The baseline seal hash; proposals and approvals are scoped to it. */
    String baselineSeal();

    /** The date the run evaluates lifecycle facts against. Fixed per run, so results are reproducible. */
    LocalDate today();

    /**
     * Where the capability reports its own activity (rounds, root-cause and blast-radius analysis,
     * routing) as it happens. Reporting is observation only and authorizes nothing.
     */
    default ActivityReporter activity() {
        return ActivityReporter.NONE;
    }
}
