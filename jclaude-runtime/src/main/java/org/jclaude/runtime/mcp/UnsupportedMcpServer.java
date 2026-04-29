package org.jclaude.runtime.mcp;

import java.util.Objects;

/** Server that the manager refused to bootstrap because its transport variant is unsupported. */
public record UnsupportedMcpServer(String server_name, McpServerSpec.Kind transport, String reason) {

    public UnsupportedMcpServer {
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(reason, "reason");
    }
}
