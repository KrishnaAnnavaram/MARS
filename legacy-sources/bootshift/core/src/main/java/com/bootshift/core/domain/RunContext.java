package com.bootshift.core.domain;

import com.bootshift.core.policy.HarnessPolicy;

import java.nio.file.Path;

/**
 * Everything a stage needs to locate itself: which run, which repository, where artifacts go, and
 * which policy is active.
 *
 * <p>{@code sourceRoot} is the user-supplied input and is treated as read-only for the entire run.
 * {@code workspaceRoot} is the external workspace root that holds original/, migration/,
 * runtime-old/, runtime-new/ and internal-checkpoint-git/.
 */
public record RunContext(String runId,
                         Path sourceRoot,
                         Path workspaceRoot,
                         OutputLayout output,
                         HarnessPolicy policy,
                         boolean aiEnabled,
                         String environmentMode) {

    public Path runWorkspace() {
        return workspaceRoot.resolve(runId);
    }

    public Path originalWorkspace() {
        return runWorkspace().resolve("original");
    }

    public Path migrationWorkspace() {
        return runWorkspace().resolve("migration");
    }

    public Path runtimeOldWorkspace() {
        return runWorkspace().resolve("runtime-old");
    }

    public Path runtimeNewWorkspace() {
        return runWorkspace().resolve("runtime-new");
    }

    public Path checkpointGit() {
        return runWorkspace().resolve("internal-checkpoint-git");
    }

    public Path evidenceStore() {
        return runWorkspace().resolve("evidence");
    }

    public Path stateStore() {
        return runWorkspace().resolve("state");
    }
}
