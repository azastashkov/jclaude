package org.jclaude.runtime.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Structured error surfaced from any MCP lifecycle phase. Mirrors the Rust {@code McpErrorSurface}.
 */
public record McpErrorSurface(
        McpLifecyclePhase phase,
        Optional<String> server_name,
        String message,
        Map<String, String> context,
        boolean recoverable,
        long timestamp_seconds) {

    public McpErrorSurface {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(message, "message");
        context = Map.copyOf(new TreeMap<>(context));
    }

    public static McpErrorSurface create(
            McpLifecyclePhase phase,
            Optional<String> server_name,
            String message,
            Map<String, String> context,
            boolean recoverable) {
        return new McpErrorSurface(
                phase, server_name, message, context, recoverable, Instant.now().getEpochSecond());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MCP lifecycle error during ")
                .append(phase)
                .append(": ")
                .append(message);
        server_name.ifPresent(name -> sb.append(" (server: ").append(name).append(")"));
        if (!context.isEmpty()) {
            sb.append(" with context ").append(context);
        }
        if (recoverable) {
            sb.append(" [recoverable]");
        }
        return sb.toString();
    }
}
