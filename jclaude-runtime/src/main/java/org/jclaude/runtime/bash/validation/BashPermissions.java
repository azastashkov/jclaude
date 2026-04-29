package org.jclaude.runtime.bash.validation;

import org.jclaude.runtime.permissions.PermissionMode;

/**
 * Permission gating for bash commands based on the active mode.
 *
 * <p>Maps a command's classified intent to the minimum {@link PermissionMode} required to run it.
 */
public final class BashPermissions {
    private BashPermissions() {}

    /** Minimum permission mode required to execute a command of the given intent. */
    public static PermissionMode required_mode(CommandIntent intent) {
        return switch (intent) {
            case READ_ONLY -> PermissionMode.READ_ONLY;
            case WRITE -> PermissionMode.WORKSPACE_WRITE;
            case DESTRUCTIVE, NETWORK, PROCESS_MANAGEMENT, PACKAGE_MANAGEMENT, SYSTEM_ADMIN, UNKNOWN -> PermissionMode
                    .DANGER_FULL_ACCESS;
        };
    }

    /** Validate that {@code activeMode} is sufficient to run a command of the given intent. */
    public static ValidationResult validate(CommandIntent intent, PermissionMode activeMode) {
        if (activeMode == PermissionMode.ALLOW || activeMode == PermissionMode.DANGER_FULL_ACCESS) {
            return ValidationResult.allow();
        }

        PermissionMode required = required_mode(intent);
        if (activeMode.compareTo(required) >= 0) {
            return ValidationResult.allow();
        }

        return ValidationResult.block(String.format(
                "Command intent '%s' requires '%s' permission, but current mode is '%s'",
                intent.name().toLowerCase().replace('_', '-'), required.as_str(), activeMode.as_str()));
    }

    /** Convenience: classify the command and check it against the active mode. */
    public static ValidationResult validate_command(String command, PermissionMode activeMode) {
        return validate(CommandSemantics.classify(command), activeMode);
    }
}
