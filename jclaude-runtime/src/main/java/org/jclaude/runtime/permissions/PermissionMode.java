package org.jclaude.runtime.permissions;

/** Permission level assigned to a tool invocation or runtime session. */
public enum PermissionMode {
    READ_ONLY,
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS,
    PROMPT,
    ALLOW;

    public String as_str() {
        return switch (this) {
            case READ_ONLY -> "read-only";
            case WORKSPACE_WRITE -> "workspace-write";
            case DANGER_FULL_ACCESS -> "danger-full-access";
            case PROMPT -> "prompt";
            case ALLOW -> "allow";
        };
    }
}
