package org.jclaude.cli.input;

import org.jclaude.runtime.permissions.PermissionPromptDecision;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.permissions.PermissionRequest;

/**
 * Permission prompter that approves every request. Used when
 * `--dangerously-skip-permissions` is passed.
 */
public final class AllowAllPermissionPrompter implements PermissionPrompter {

    public static final AllowAllPermissionPrompter INSTANCE = new AllowAllPermissionPrompter();

    private AllowAllPermissionPrompter() {}

    @Override
    public PermissionPromptDecision decide(PermissionRequest request) {
        return new PermissionPromptDecision.Allow();
    }
}
