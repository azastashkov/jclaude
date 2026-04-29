package org.jclaude.runtime.permissions;

/** Prompting interface used when policy requires interactive approval. */
@FunctionalInterface
public interface PermissionPrompter {
    PermissionPromptDecision decide(PermissionRequest request);
}
