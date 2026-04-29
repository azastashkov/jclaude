package org.jclaude.runtime.permissions;

/** Final authorization result after evaluating static rules and prompts. */
public sealed interface PermissionOutcome {
    record Allow() implements PermissionOutcome {}

    record Deny(String reason) implements PermissionOutcome {}
}
