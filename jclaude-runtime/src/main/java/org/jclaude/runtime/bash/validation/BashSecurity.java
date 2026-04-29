package org.jclaude.runtime.bash.validation;

import java.nio.file.Path;

/**
 * Security checks combining destructive-command warnings and path traversal validation.
 *
 * <p>Returns the first non-Allow result, or Allow if both checks pass.
 */
public final class BashSecurity {
    private BashSecurity() {}

    public static ValidationResult validate(String command, Path workspace) {
        ValidationResult destructive = DestructiveCommandWarning.check(command);
        if (!(destructive instanceof ValidationResult.Allow)) {
            return destructive;
        }
        return PathValidation.validate(command, workspace);
    }
}
