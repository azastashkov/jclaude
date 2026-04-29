package org.jclaude.runtime.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Configuration record describing one MCP server. Mirrors the Rust {@code McpClientBootstrap} +
 * {@code McpClientTransport} pair: a single record with one of several transport variants. The
 * {@link Kind} discriminator is matched against the optional fields to produce a typed transport
 * at startup time.
 */
public record McpServerSpec(
        String server_name,
        Kind kind,
        Optional<String> command,
        List<String> args,
        Map<String, String> env,
        Optional<String> url,
        Map<String, String> headers,
        Optional<Duration> tool_call_timeout) {

    public enum Kind {
        STDIO,
        HTTP,
        SSE,
        WEBSOCKET
    }

    public McpServerSpec {
        Objects.requireNonNull(server_name, "server_name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(tool_call_timeout, "tool_call_timeout");
        args = List.copyOf(args);
        env = Map.copyOf(new TreeMap<>(env));
        headers = Map.copyOf(new TreeMap<>(headers));
    }

    public static McpServerSpec stdio(String server_name, String command, List<String> args, Map<String, String> env) {
        return new McpServerSpec(
                server_name, Kind.STDIO, Optional.of(command), args, env, Optional.empty(), Map.of(), Optional.empty());
    }

    public static McpServerSpec http(String server_name, String url, Map<String, String> headers) {
        return new McpServerSpec(
                server_name,
                Kind.HTTP,
                Optional.empty(),
                List.of(),
                Map.of(),
                Optional.of(url),
                headers,
                Optional.empty());
    }

    public static McpServerSpec sse(String server_name, String url, Map<String, String> headers) {
        return new McpServerSpec(
                server_name,
                Kind.SSE,
                Optional.empty(),
                List.of(),
                Map.of(),
                Optional.of(url),
                headers,
                Optional.empty());
    }

    public static McpServerSpec websocket(String server_name, String url, Map<String, String> headers) {
        return new McpServerSpec(
                server_name,
                Kind.WEBSOCKET,
                Optional.empty(),
                List.of(),
                Map.of(),
                Optional.of(url),
                headers,
                Optional.empty());
    }

    public McpServerSpec with_tool_call_timeout(Duration timeout) {
        return new McpServerSpec(server_name, kind, command, args, env, url, headers, Optional.ofNullable(timeout));
    }
}
