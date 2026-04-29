package org.jclaude.runtime.bash.validation;

import java.nio.file.Path;

/** Validate that command paths don't include suspicious traversal patterns. */
public final class PathValidation {
    private PathValidation() {}

    public static ValidationResult validate(String command, Path workspace) {
        if (command.contains("../")) {
            String workspaceStr = workspace.toString();
            if (!command.contains(workspaceStr)) {
                return ValidationResult.warn(
                        "Command contains directory traversal pattern '../' — verify the target path resolves within the workspace");
            }
        }

        if (command.contains("~/") || command.contains("$HOME")) {
            return ValidationResult.warn(
                    "Command references home directory — verify it stays within the workspace scope");
        }

        return ValidationResult.allow();
    }
}
