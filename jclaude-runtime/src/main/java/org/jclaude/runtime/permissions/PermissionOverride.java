package org.jclaude.runtime.permissions;

/** Hook-provided override applied before standard permission evaluation. */
public enum PermissionOverride {
    ALLOW,
    DENY,
    ASK
}
