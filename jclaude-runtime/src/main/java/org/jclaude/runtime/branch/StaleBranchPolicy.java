package org.jclaude.runtime.branch;

/** Stale branch policy enum. */
public enum StaleBranchPolicy {
    AUTO_REBASE,
    AUTO_MERGE_FORWARD,
    WARN_ONLY,
    BLOCK
}
