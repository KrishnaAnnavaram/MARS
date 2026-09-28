package com.mars.harness.controlcenter.observability;

import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.run.RunCoordinator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

/**
 * Readiness of the Control Center's filesystem boundary: the runs root the engine writes and the
 * harness installation it reads. Reports how many engine operations are running.
 */
@Component("marsRuns")
public class RunsRootHealthIndicator implements HealthIndicator {

    private final ControlCenterPaths paths;
    private final RunCoordinator coordinator;

    public RunsRootHealthIndicator(ControlCenterPaths paths, RunCoordinator coordinator) {
        this.paths = paths;
        this.coordinator = coordinator;
    }

    @Override
    public Health health() {
        boolean harness = Files.isRegularFile(paths.harnessRoot().resolve("policies/default/unified-policy.json"));
        boolean runs = Files.isDirectory(paths.runsRoot()) ? Files.isWritable(paths.runsRoot())
                : paths.runsRoot().getParent() != null && Files.isWritable(paths.runsRoot().getParent());
        Health.Builder builder = harness && runs ? Health.up() : Health.down();
        return builder.withDetail("harness_installation", harness ? "found" : "missing policies/default/unified-policy.json")
                .withDetail("runs_root", runs ? "writable" : "not writable")
                .withDetail("repository_roots", paths.repositoryRoots().size())
                .withDetail("engine_operations_running", coordinator.allRunning().size()).build();
    }
}
