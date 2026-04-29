package org.jclaude.runtime.bash.validation;

import org.jclaude.runtime.permissions.PermissionMode;

/** Validate sed expressions for safety. */
public final class SedValidation {
    private SedValidation() {}

    public static ValidationResult validate(String command, PermissionMode mode) {
        String first = CommandHelpers.extract_first_command(command);
        if (!"sed".equals(first)) {
            return ValidationResult.allow();
        }

        if (mode == PermissionMode.READ_ONLY && command.contains(" -i")) {
            return ValidationResult.block("sed -i (in-place editing) is not allowed in read-only mode");
        }

        return ValidationResult.allow();
    }
}
