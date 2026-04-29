package org.jclaude.runtime.mcp;

import java.util.Objects;

/** A server that failed to bring up successfully, with the lifecycle phase that surfaced it. */
public record McpFailedServer(String server_name, McpLifecyclePhase phase, McpErrorSurface error) {

    public McpFailedServer {
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(error, "error");
    }
}
