package org.jclaude.runtime.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Per-server discovery failure as captured by the best-effort tool-discovery report. */
public record McpDiscoveryFailure(
        String server_name, McpLifecyclePhase phase, String error, boolean recoverable, Map<String, String> context) {

    public McpDiscoveryFailure {
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(context, "context");
        context = Map.copyOf(new TreeMap<>(context));
    }
}
