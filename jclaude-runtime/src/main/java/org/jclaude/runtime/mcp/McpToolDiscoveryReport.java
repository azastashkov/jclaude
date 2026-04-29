package org.jclaude.runtime.mcp;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Best-effort report produced by {@link McpServerManager#discover_tools_best_effort()}: includes
 * successfully-discovered tools, per-server failures, unsupported transports and an optional
 * degraded-startup descriptor.
 */
public record McpToolDiscoveryReport(
        List<ManagedMcpTool> tools,
        List<McpDiscoveryFailure> failed_servers,
        List<UnsupportedMcpServer> unsupported_servers,
        Optional<McpDegradedReport> degraded_startup) {

    public McpToolDiscoveryReport {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(failed_servers, "failed_servers");
        Objects.requireNonNull(unsupported_servers, "unsupported_servers");
        Objects.requireNonNull(degraded_startup, "degraded_startup");
        tools = List.copyOf(tools);
        failed_servers = List.copyOf(failed_servers);
        unsupported_servers = List.copyOf(unsupported_servers);
    }
}
