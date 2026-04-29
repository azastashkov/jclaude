package org.jclaude.runtime.bash.validation;

import java.util.List;
import org.jclaude.runtime.permissions.PermissionMode;

/** Validate that a command is consistent with the given permission mode. */
public final class ModeValidation {
    private static final List<String> SYSTEM_PATHS =
            List.of("/etc/", "/usr/", "/var/", "/boot/", "/sys/", "/proc/", "/dev/", "/sbin/", "/lib/", "/opt/");

    private ModeValidation() {}

    public static ValidationResult validate(String command, PermissionMode mode) {
        return switch (mode) {
            case READ_ONLY -> ReadOnlyValidation.validate(command, mode);
            case WORKSPACE_WRITE -> {
                if (command_targets_outside_workspace(command)) {
                    yield ValidationResult.warn(
                            "Command appears to target files outside the workspace — requires elevated permission");
                }
                yield ValidationResult.allow();
            }
            case DANGER_FULL_ACCESS, ALLOW, PROMPT -> ValidationResult.allow();
        };
    }

    /** Heuristic: does the command reference absolute paths outside typical workspace dirs? */
    static boolean command_targets_outside_workspace(String command) {
        String first = CommandHelpers.extract_first_command(command);
        boolean isWriteCmd = ReadOnlyValidation.WRITE_COMMANDS.contains(first)
                || ReadOnlyValidation.STATE_MODIFYING_COMMANDS.contains(first);

        if (!isWriteCmd) {
            return false;
        }

        for (String sysPath : SYSTEM_PATHS) {
            if (command.contains(sysPath)) {
                return true;
            }
        }
        return false;
    }
}
