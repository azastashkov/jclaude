package org.jclaude.runtime.permissions;

/** User-facing decision returned by a {@link PermissionPrompter}. */
public sealed interface PermissionPromptDecision {
    record Allow() implements PermissionPromptDecision {}

    record Deny(String reason) implements PermissionPromptDecision {}
}
