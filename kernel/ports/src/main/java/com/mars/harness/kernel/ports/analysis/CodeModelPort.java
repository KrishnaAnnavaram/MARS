package com.mars.harness.kernel.ports.analysis;

import com.mars.harness.kernel.core.identity.observe.CodeObservation;

/**
 * Language-aware code observation for identity (spec §27 {@code CodeModelPort} and
 * {@code StatementIdentityResolver}).
 *
 * <p>It produces observations, never identities. Deciding identity is the kernel's job.
 */
public interface CodeModelPort {

    /** True when this adapter can observe files of the given repository-relative path. */
    boolean supports(String path);

    /**
     * Observes one file. A file that cannot be parsed yields an observation with no entities
     * and an issue. It never yields a partial guess.
     */
    CodeObservation observe(String fileId, String path, String moduleId, String content);

    /**
     * Normalises a statement's source text exactly as {@link #observe} does, so a provider's
     * "rewrote STMT-x to this text" hint compares like with like.
     */
    String normalizeStatement(String statementSource);
}
