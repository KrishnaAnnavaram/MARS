package com.mars.harness.cli;

import com.mars.harness.capabilities.migration.SpringMigrationCapability;
import com.mars.harness.capabilities.security.VulnerabilityRemediationCapability;
import com.mars.harness.kernel.adapters.excel.IssueRegisterNormalizer;
import com.mars.harness.kernel.adapters.javaparser.JavaCodeObserver;
import com.mars.harness.kernel.adapters.lifecycle.BootshiftLifecycleAdapter;
import com.mars.harness.kernel.adapters.maven.MavenBuildRunner;
import com.mars.harness.kernel.adapters.runtime.JarProbeRuntime;
import com.mars.harness.kernel.adapters.sarif.SarifNormalizer;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.HarnessEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * The composition root: the one place concrete adapters and capability packs are wired into the
 * kernel engine. The kernel never constructs any of them, which is what keeps it
 * vendor-independent and testable.
 */
public final class HarnessFactory {

    private HarnessFactory() {
    }

    public static EngineConfig config(Path harnessRoot, Path runsRoot, Path policyFile, LocalDate today, boolean mavenOffline,
                                      boolean network) {
        Path root = harnessRoot.toAbsolutePath().normalize();
        Path policyPath = policyFile != null ? policyFile : root.resolve("policies/default/unified-policy.json");
        UnifiedPolicy policy = UnifiedPolicy.load(policyPath);
        Path runs = runsRoot.toAbsolutePath().normalize();
        return new EngineConfig(root, root.resolve("legacy-sources/bootshift"), runs, policy,
                new MavenBuildRunner(Duration.ofSeconds(policy.migration().roundTimeoutSeconds()), mavenOffline),
                new JarProbeRuntime(), new BootshiftLifecycleAdapter(runs.resolve(".lifecycle-cache"), network),
                new JavaCodeObserver(), List.of(new IssueRegisterNormalizer(), new SarifNormalizer()),
                new SpringMigrationCapability(), new VulnerabilityRemediationCapability(), "production", network,
                today == null ? LocalDate.now() : today);
    }

    public static HarnessEngine engine(EngineConfig config) {
        return new HarnessEngine(config);
    }

    /**
     * Locates the harness installation: {@code HARNESS_HOME}, then the working directory and its
     * parents, then the jar's location and its parents. The marker is the unified policy file.
     */
    public static Path locateHarnessRoot(Path explicit) {
        if (explicit != null) {
            return explicit;
        }
        String env = System.getenv("HARNESS_HOME");
        if (env != null && isRoot(Path.of(env))) {
            return Path.of(env);
        }
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (isRoot(p)) {
                return p;
            }
        }
        try {
            Path jar = Path.of(HarnessFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path p = jar; p != null; p = p.getParent()) {
                if (isRoot(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
            // fall through to the refusal
        }
        throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                "Cannot locate the harness installation (policies/default/unified-policy.json); set HARNESS_HOME or --harness-root");
    }

    private static boolean isRoot(Path p) {
        return Files.isRegularFile(p.resolve("policies/default/unified-policy.json"))
                && Files.isDirectory(p.resolve("legacy-sources/bootshift"));
    }
}
