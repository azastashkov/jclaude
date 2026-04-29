package org.jclaude.runtime.sandbox;

/** Filesystem isolation modes mirrored from Rust. */
public enum FilesystemIsolationMode {
    OFF("off"),
    WORKSPACE_ONLY("workspace-only"),
    ALLOW_LIST("allow-list");

    private final String wire;

    FilesystemIsolationMode(String wire) {
        this.wire = wire;
    }

    public String as_str() {
        return wire;
    }

    public static FilesystemIsolationMode default_mode() {
        return WORKSPACE_ONLY;
    }
}
