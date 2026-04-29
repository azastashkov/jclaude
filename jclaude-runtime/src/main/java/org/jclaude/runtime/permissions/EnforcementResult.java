package org.jclaude.runtime.permissions;

/** Result returned by {@link PermissionEnforcer}. */
public sealed interface EnforcementResult {
    record Allowed() implements EnforcementResult {}

    record Denied(String tool, String active_mode, String required_mode, String reason) implements EnforcementResult {}
}
