package org.jclaude.runtime.mcp;

import java.util.Objects;

/**
 * A tool exposed by a managed MCP server, alongside the qualified name used by the wider runtime
 * tool dispatcher and the raw name that the server itself recognises.
 */
public record ManagedMcpTool(String server_name, String qualified_name, String raw_name, McpTool tool) {

    public ManagedMcpTool {
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(qualified_name, "qualified_name");
        Objects.requireNonNull(raw_name, "raw_name");
        Objects.requireNonNull(tool, "tool");
    }
}
