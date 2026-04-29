package org.jclaude.runtime.bash.validation;

import org.jclaude.runtime.permissions.PermissionMode;

/**
 * Sandbox decision logic.
 *
 * <p>Decides whether a bash command should be executed inside a sandbox. Read-only commands skip
 * the sandbox; anything that may write, network, or modify state runs sandboxed unless the active
 * mode is {@code DANGER_FULL_ACCESS} or {@code ALLOW}.
 */
public final class ShouldUseSandbox {
    private ShouldUseSandbox() {}

    public static boolean decide(String command, PermissionMode mode) {
        return decide(CommandSemantics.classify(command), mode);
    }

    public static boolean decide(CommandIntent intent, PermissionMode mode) {
        if (mode == PermissionMode.DANGER_FULL_ACCESS || mode == PermissionMode.ALLOW) {
            return false;
        }
        return switch (intent) {
            case READ_ONLY -> false;
            case WRITE, DESTRUCTIVE, NETWORK, PROCESS_MANAGEMENT, PACKAGE_MANAGEMENT, SYSTEM_ADMIN, UNKNOWN -> true;
        };
    }
}
