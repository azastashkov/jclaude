package org.jclaude.cli;

import java.util.Locale;
import org.jclaude.runtime.permissions.PermissionMode;

/** Maps the CLI string form of `--permission-mode` to the runtime enum. */
public enum PermissionModeOption {
    READ_ONLY("read-only", PermissionMode.READ_ONLY),
    WORKSPACE_WRITE("workspace-write", PermissionMode.WORKSPACE_WRITE),
    DANGER_FULL_ACCESS("danger-full-access", PermissionMode.DANGER_FULL_ACCESS);

    private final String wire;
    private final PermissionMode runtime;

    PermissionModeOption(String wire, PermissionMode runtime) {
        this.wire = wire;
        this.runtime = runtime;
    }

    public String wire() {
        return wire;
    }

    public PermissionMode runtime() {
        return runtime;
    }

    public static PermissionModeOption parse(String raw) {
        if (raw == null) {
            return READ_ONLY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (PermissionModeOption mode : values()) {
            if (mode.wire.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown permission mode: " + raw);
    }
}
