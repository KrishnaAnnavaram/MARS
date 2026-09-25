package com.mars.harness.kernel.ports.execution;

import java.nio.file.Path;

/**
 * Disposable copies of the workspace for running builds and applications (ADR-U007).
 *
 * <p>Build tools and running applications write files (target/, databases, caches). Running them
 * inside the tracked workspace would make that output look like an unauthorized mutation, or
 * worse, let it become one. They run in an exec copy that is synchronised from the workspace
 * before each use and never copied back.
 */
public interface ExecutionSandbox {

    /** Synchronises and returns the exec copy for a label, e.g. {@code round-03} or {@code baseline}. */
    Path prepare(String label);

    /** The immutable pre-mutation snapshot of the repository. */
    Path snapshot();
}
