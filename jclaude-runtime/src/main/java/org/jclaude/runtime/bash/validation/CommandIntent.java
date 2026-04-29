package org.jclaude.runtime.bash.validation;

/** Semantic classification of a bash command's intent. */
public enum CommandIntent {
    /** Read-only operations: ls, cat, grep, find, etc. */
    READ_ONLY,
    /** File system writes: cp, mv, mkdir, touch, tee, etc. */
    WRITE,
    /** Destructive operations: rm, shred, truncate, etc. */
    DESTRUCTIVE,
    /** Network operations: curl, wget, ssh, etc. */
    NETWORK,
    /** Process management: kill, pkill, etc. */
    PROCESS_MANAGEMENT,
    /** Package management: apt, brew, pip, npm, etc. */
    PACKAGE_MANAGEMENT,
    /** System administration: sudo, chmod, chown, mount, etc. */
    SYSTEM_ADMIN,
    /** Unknown or unclassifiable command. */
    UNKNOWN
}
