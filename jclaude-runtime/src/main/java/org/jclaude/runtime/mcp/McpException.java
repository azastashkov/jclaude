package org.jclaude.runtime.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcError;

/**
 * Hierarchy of structured MCP errors. Mirrors the Rust {@code McpServerManagerError} enum, with
 * each variant modelled as a sealed-record subtype so callers can pattern-match phase / method /
 * timeout context cleanly.
 */
public sealed class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }

    public McpLifecyclePhase phase() {
        return McpLifecyclePhase.ERROR_SURFACING;
    }

    public boolean recoverable() {
        return false;
    }

    public Map<String, String> error_context() {
        return Map.of();
    }

    public McpFailedServer to_failed_server(String server_name) {
        return new McpFailedServer(
                server_name,
                phase(),
                McpErrorSurface.create(
                        phase(), Optional.of(server_name), getMessage(), error_context(), recoverable()));
    }

    public static final class TransportFailure extends McpException {

        private static final long serialVersionUID = 1L;
        private final String server_name;
        private final String method;

        public TransportFailure(String server_name, String method, Throwable cause) {
            super(
                    "MCP server `" + server_name + "` transport failed during " + method + ": " + cause.getMessage(),
                    cause);
            this.server_name = Objects.requireNonNull(server_name, "server_name");
            this.method = Objects.requireNonNull(method, "method");
        }

        public String server_name() {
            return server_name;
        }

        public String method() {
            return method;
        }

        @Override
        public McpLifecyclePhase phase() {
            return phaseForMethod(method);
        }

        @Override
        public boolean recoverable() {
            return phase() != McpLifecyclePhase.INITIALIZE_HANDSHAKE;
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of(
                    "server",
                    server_name,
                    "method",
                    method,
                    "io_kind",
                    String.valueOf(
                            getCause() == null ? "" : getCause().getClass().getSimpleName()));
        }
    }

    public static final class JsonRpcFailure extends McpException {

        private static final long serialVersionUID = 1L;
        private final String server_name;
        private final String method;
        private final JsonRpcError error;

        public JsonRpcFailure(String server_name, String method, JsonRpcError error) {
            super("MCP server `" + server_name + "` returned JSON-RPC error for " + method + ": " + error.message()
                    + " (" + error.code() + ")");
            this.server_name = server_name;
            this.method = method;
            this.error = error;
        }

        public String server_name() {
            return server_name;
        }

        public String method() {
            return method;
        }

        public JsonRpcError error() {
            return error;
        }

        @Override
        public McpLifecyclePhase phase() {
            return phaseForMethod(method);
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of("server", server_name, "method", method, "jsonrpc_code", String.valueOf(error.code()));
        }
    }

    public static final class InvalidResponse extends McpException {

        private static final long serialVersionUID = 1L;
        private final String server_name;
        private final String method;
        private final String details;

        public InvalidResponse(String server_name, String method, String details) {
            super("MCP server `" + server_name + "` returned invalid response for " + method + ": " + details);
            this.server_name = server_name;
            this.method = method;
            this.details = details;
        }

        public String server_name() {
            return server_name;
        }

        public String method() {
            return method;
        }

        public String details() {
            return details;
        }

        @Override
        public McpLifecyclePhase phase() {
            return phaseForMethod(method);
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of("server", server_name, "method", method, "details", details);
        }
    }

    public static final class TimeoutFailure extends McpException {

        private static final long serialVersionUID = 1L;
        private final String server_name;
        private final String method;
        private final long timeout_ms;

        public TimeoutFailure(String server_name, String method, long timeout_ms) {
            super("MCP server `" + server_name + "` timed out after " + timeout_ms + " ms while handling " + method);
            this.server_name = server_name;
            this.method = method;
            this.timeout_ms = timeout_ms;
        }

        public String server_name() {
            return server_name;
        }

        public String method() {
            return method;
        }

        public long timeout_ms() {
            return timeout_ms;
        }

        @Override
        public McpLifecyclePhase phase() {
            return phaseForMethod(method);
        }

        @Override
        public boolean recoverable() {
            return phase() != McpLifecyclePhase.INITIALIZE_HANDSHAKE;
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of("server", server_name, "method", method, "timeout_ms", String.valueOf(timeout_ms));
        }
    }

    public static final class UnknownTool extends McpException {

        private static final long serialVersionUID = 1L;
        private final String qualified_name;

        public UnknownTool(String qualified_name) {
            super("unknown MCP tool `" + qualified_name + "`");
            this.qualified_name = qualified_name;
        }

        public String qualified_name() {
            return qualified_name;
        }

        @Override
        public McpLifecyclePhase phase() {
            return McpLifecyclePhase.TOOL_DISCOVERY;
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of("qualified_tool", qualified_name);
        }
    }

    public static final class UnknownServer extends McpException {

        private static final long serialVersionUID = 1L;
        private final String server_name;

        public UnknownServer(String server_name) {
            super("unknown MCP server `" + server_name + "`");
            this.server_name = server_name;
        }

        public String server_name() {
            return server_name;
        }

        @Override
        public McpLifecyclePhase phase() {
            return McpLifecyclePhase.SERVER_REGISTRATION;
        }

        @Override
        public Map<String, String> error_context() {
            return Map.of("server", server_name);
        }
    }

    private static McpLifecyclePhase phaseForMethod(String method) {
        return switch (method) {
            case "initialize" -> McpLifecyclePhase.INITIALIZE_HANDSHAKE;
            case "tools/list" -> McpLifecyclePhase.TOOL_DISCOVERY;
            case "resources/list" -> McpLifecyclePhase.RESOURCE_DISCOVERY;
            case "resources/read", "tools/call" -> McpLifecyclePhase.INVOCATION;
            default -> McpLifecyclePhase.ERROR_SURFACING;
        };
    }

    /** Convenience helper used by manager error reporting (mirrors Rust's helper). */
    public McpFailedServer to_failed_server_with_context(String server_name) {
        Map<String, String> context = new TreeMap<>(error_context());
        return new McpFailedServer(
                server_name,
                phase(),
                McpErrorSurface.create(phase(), Optional.of(server_name), getMessage(), context, recoverable()));
    }
}
