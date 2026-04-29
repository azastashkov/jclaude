package org.jclaude.plugins;

import java.util.Optional;

/** Per-tool permission tier for a plugin tool, mirroring the Rust enum. */
public enum PluginToolPermission {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access");

    private final String label;

    PluginToolPermission(String label) {
        this.label = label;
    }

    public String as_str() {
        return label;
    }

    public static Optional<PluginToolPermission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case "read-only" -> Optional.of(READ_ONLY);
            case "workspace-write" -> Optional.of(WORKSPACE_WRITE);
            case "danger-full-access" -> Optional.of(DANGER_FULL_ACCESS);
            default -> Optional.empty();
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
