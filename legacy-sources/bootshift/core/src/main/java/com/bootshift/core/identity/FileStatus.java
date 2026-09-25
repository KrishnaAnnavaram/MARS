package com.bootshift.core.identity;

/** Lifecycle status of a registered file. Identity is never erased - deletion is a status. */
public enum FileStatus {
    ACTIVE,
    DELETED,
    MERGED_AWAY
}
