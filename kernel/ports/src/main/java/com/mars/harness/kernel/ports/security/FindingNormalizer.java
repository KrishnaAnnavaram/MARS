package com.mars.harness.kernel.ports.security;

import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;
import com.mars.harness.kernel.ports.identity.IdentityView;

import java.nio.file.Path;
import java.util.List;

/**
 * Converts an external finding source into canonical {@link Finding}s anchored to identity
 * (spec §14). Initial sources: the VRH Excel issue register and SARIF 2.1.0. None is the system
 * of record.
 */
public interface FindingNormalizer {

    String sourceName();

    boolean accepts(Path input);

    List<Finding> normalize(Path input, IdentityView identity, EvidenceStore evidence, String runId);
}
