package com.bootshift.core.identity;

/**
 * How a rename was established, in descending order of trust (spec section 11.3). The harness
 * records the source so a low-confidence similarity match is never presented as a provider-reported
 * fact.
 */
public enum RenameSource {
    PROVIDER,
    GIT,
    SIMILARITY,
    NONE
}
