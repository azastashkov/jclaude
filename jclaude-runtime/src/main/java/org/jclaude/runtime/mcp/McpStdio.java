package org.jclaude.runtime.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jclaude.runtime.mcp.jsonrpc.transport.HttpTransport;
import org.jclaude.runtime.mcp.jsonrpc.transport.SseTransport;
import org.jclaude.runtime.mcp.jsonrpc.transport.StdioTransport;
import org.jclaude.runtime.mcp.jsonrpc.transport.Transport;
import org.jclaude.runtime.mcp.jsonrpc.transport.WebSocketTransport;

/**
 * Stdio-specific MCP helpers and protocol constants. The Rust {@code McpStdioProcess} is mostly
 * subsumed by {@link StdioTransport}; the constants and tiny adapter helpers here mirror the
 * remaining surface from {@code mcp_stdio.rs} (timeouts, default initialize parameters,
 * transport spawn helper).
 */
public final class McpStdio {

    public static final long DEFAULT_TOOL_CALL_TIMEOUT_MS = 60_000;
    public static final long DEFAULT_INITIALIZE_TIMEOUT_MS = 10_000;
    public static final long DEFAULT_LIST_TIMEOUT_MS = 30_000;

    private McpStdio() {}

    /** Spawns the transport selected by the spec's {@link McpServerSpec.Kind}. */
    public static Transport spawn_transport(McpServerSpec spec) {
        return switch (spec.kind()) {
            case STDIO -> spawn_stdio(spec);
            case HTTP -> new HttpTransport(spec.url().orElseThrow(), spec.headers());
            case SSE -> new SseTransport(spec.url().orElseThrow(), spec.headers());
            case WEBSOCKET -> new WebSocketTransport(spec.url().orElseThrow(), spec.headers());
        };
    }

    public static StdioTransport spawn_stdio(McpServerSpec spec) {
        if (spec.kind() != McpServerSpec.Kind.STDIO) {
            throw new IllegalArgumentException(
                    "MCP bootstrap transport for " + spec.server_name() + " is not stdio: " + spec.kind());
        }
        String command = spec.command()
                .orElseThrow(() -> new IllegalArgumentException(
                        "stdio MCP server `" + spec.server_name() + "` has no command configured"));
        return new StdioTransport(command, spec.args(), spec.env());
    }

    public static Duration resolved_tool_call_timeout(McpServerSpec spec) {
        return spec.tool_call_timeout().orElse(Duration.ofMillis(DEFAULT_TOOL_CALL_TIMEOUT_MS));
    }

    /** Helper for tests to build a stdio spec without keeping an explicit factory call. */
    public static McpServerSpec build_stdio_spec(
            String name, String command, List<String> args, Map<String, String> env) {
        return McpServerSpec.stdio(name, command, args, env);
    }
}
