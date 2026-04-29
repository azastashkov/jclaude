package org.jclaude.runtime.bash.validation;

import java.nio.file.Path;
import org.jclaude.runtime.permissions.PermissionMode;

/**
 * Top-level orchestrator for the bash command validation pipeline.
 *
 * <p>Runs all submodules in the canonical order:
 *
 * <ol>
 *   <li>{@link ModeValidation} (which delegates to {@link ReadOnlyValidation} when in read-only)
 *   <li>{@link SedValidation}
 *   <li>{@link DestructiveCommandWarning}
 *   <li>{@link PathValidation}
 * </ol>
 *
 * Returns the first non-Allow result, or Allow if all validations pass.
 */
public final class BashValidator {
    private BashValidator() {}

    public static ValidationResult validate(String command, PermissionMode mode, Path workspace) {
        ValidationResult result = ModeValidation.validate(command, mode);
        if (!(result instanceof ValidationResult.Allow)) {
            return result;
        }

        result = SedValidation.validate(command, mode);
        if (!(result instanceof ValidationResult.Allow)) {
            return result;
        }

        result = DestructiveCommandWarning.check(command);
        if (!(result instanceof ValidationResult.Allow)) {
            return result;
        }

        return PathValidation.validate(command, workspace);
    }
}
