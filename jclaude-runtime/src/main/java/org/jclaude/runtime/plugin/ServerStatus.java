package org.jclaude.runtime.plugin;

import java.util.Locale;

/** Health status of an MCP server within a plugin. */
public enum ServerStatus {
    HEALTHY,
    DEGRADED,
    FAILED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
