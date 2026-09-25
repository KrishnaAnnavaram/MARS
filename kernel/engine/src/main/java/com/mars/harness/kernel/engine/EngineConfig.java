package com.mars.harness.kernel.engine;

import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.ports.analysis.CodeModelPort;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.runtime.RuntimePort;
import com.mars.harness.kernel.ports.security.FindingNormalizer;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the composition root (the CLI) wires into the kernel engine. The engine never
 * constructs a capability or a concrete tool adapter itself, which is what lets tests substitute
 * doubles for the build and runtime tools without touching kernel logic.
 *
 * @param bootshiftHome   {@code legacy-sources/bootshift}: schemas, policies and rules Bootshift
 *                        reads at runtime
 * @param bootshiftPolicy Bootshift policy profile for the analysis stages it runs
 * @param today           the evaluation date for lifecycle facts (fixed per run for
 *                        reproducibility)
 */
public record EngineConfig(Path harnessRoot, Path bootshiftHome, Path runsRoot, UnifiedPolicy policy,
                           BuildPort build, RuntimePort runtime, LifecyclePort lifecycle, CodeModelPort codeModel,
                           List<FindingNormalizer> normalizers, MigrationCapability migration,
                           RemediationCapability remediation, String bootshiftPolicy, boolean networkEnabled,
                           LocalDate today) {

    public EngineConfig {
        normalizers = normalizers == null ? List.of() : List.copyOf(normalizers);
    }

    public EngineConfig withRuntime(BuildPort newBuild, RuntimePort newRuntime) {
        return new EngineConfig(harnessRoot, bootshiftHome, runsRoot, policy, newBuild, newRuntime, lifecycle, codeModel,
                normalizers, migration, remediation, bootshiftPolicy, networkEnabled, today);
    }
}
