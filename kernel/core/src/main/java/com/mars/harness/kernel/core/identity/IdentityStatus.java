package com.mars.harness.kernel.core.identity;

/** Identity is never erased. Deletion and merge are statuses, as in Bootshift's FileStatus. */
public enum IdentityStatus {
    ACTIVE,
    DELETED,
    MERGED_AWAY
}
