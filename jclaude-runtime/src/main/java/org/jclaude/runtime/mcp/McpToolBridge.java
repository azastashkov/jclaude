package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * In-runtime registry that bridges connected MCP servers into the surrounding tool dispatch
 * surface. Mirrors the Rust {@code McpToolRegistry} from {@code mcp_tool_bridge.rs} — runtime
 * code populates the registry with discovery results, and downstream consumers (the
 * {@code jclaude-tools} {@code ToolDispatcher}) read and invoke tools through it.
 *
 * <p>The runtime can't depend on {@code jclaude-tools} (the dependency edge runs the other way),
 * so this bridge exposes plain records / JSON nodes rather than {@code ToolSpec}s. Adapter code
 * in the tools layer wraps the registry's data into {@code ToolSpec} instances at dispatch time.
 */
public final class McpToolBridge {

    public enum ConnectionStatus {
        DISCONNECTED("disconnected"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        AUTH_REQUIRED("auth_required"),
        ERROR("error");

        private final String wire;

        ConnectionStatus(String wire) {
            this.wire = wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    public record McpResourceInfo(String uri, String name, Optional<String> description, Optional<String> mime_type) {

        public McpResourceInfo {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(name, "name");
            description = description == null ? Optional.empty() : description;
            mime_type = mime_type == null ? Optional.empty() : mime_type;
        }
    }

    public record McpToolInfo(String name, Optional<String> description, Optional<JsonNode> input_schema) {

        public McpToolInfo {
            Objects.requireNonNull(name, "name");
            description = description == null ? Optional.empty() : description;
            input_schema = input_schema == null ? Optional.empty() : input_schema;
        }
    }

    public record McpServerState(
            String server_name,
            ConnectionStatus status,
            List<McpToolInfo> tools,
            List<McpResourceInfo> resources,
            Optional<String> server_info,
            Optional<String> error_message) {

        public McpServerState {
            Objects.requireNonNull(server_name, "server_name");
            Objects.requireNonNull(status, "status");
            tools = List.copyOf(tools);
            resources = List.copyOf(resources);
            server_info = server_info == null ? Optional.empty() : server_info;
            error_message = error_message == null ? Optional.empty() : error_message;
        }
    }

    private final Object lock = new Object();
    private final Map<String, McpServerState> servers = new HashMap<>();
    private final AtomicReference<McpServerManager> manager_ref = new AtomicReference<>();

    public McpToolBridge() {}

    /** Set the underlying manager. Returns false if a manager has already been bound. */
    public boolean set_manager(McpServerManager manager) {
        return manager_ref.compareAndSet(null, Objects.requireNonNull(manager, "manager"));
    }

    public Optional<McpServerManager> manager() {
        return Optional.ofNullable(manager_ref.get());
    }

    public void register_server(
            String server_name,
            ConnectionStatus status,
            List<McpToolInfo> tools,
            List<McpResourceInfo> resources,
            Optional<String> server_info) {
        synchronized (lock) {
            servers.put(
                    server_name,
                    new McpServerState(server_name, status, tools, resources, server_info, Optional.empty()));
        }
    }

    public Optional<McpServerState> get_server(String server_name) {
        synchronized (lock) {
            return Optional.ofNullable(servers.get(server_name));
        }
    }

    public List<McpServerState> list_servers() {
        synchronized (lock) {
            return List.copyOf(servers.values());
        }
    }

    public List<McpResourceInfo> list_resources(String server_name) {
        synchronized (lock) {
            McpServerState state = servers.get(server_name);
            if (state == null) {
                throw new IllegalArgumentException("server '" + server_name + "' not found");
            }
            if (state.status() != ConnectionStatus.CONNECTED) {
                throw new IllegalStateException(
                        "server '" + server_name + "' is not connected (status: " + state.status() + ")");
            }
            return List.copyOf(state.resources());
        }
    }

    public McpResourceInfo read_resource(String server_name, String uri) {
        synchronized (lock) {
            McpServerState state = servers.get(server_name);
            if (state == null) {
                throw new IllegalArgumentException("server '" + server_name + "' not found");
            }
            if (state.status() != ConnectionStatus.CONNECTED) {
                throw new IllegalStateException(
                        "server '" + server_name + "' is not connected (status: " + state.status() + ")");
            }
            return state.resources().stream()
                    .filter(r -> r.uri().equals(uri))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "resource '" + uri + "' not found on server '" + server_name + "'"));
        }
    }

    public List<McpToolInfo> list_tools(String server_name) {
        synchronized (lock) {
            McpServerState state = servers.get(server_name);
            if (state == null) {
                throw new IllegalArgumentException("server '" + server_name + "' not found");
            }
            if (state.status() != ConnectionStatus.CONNECTED) {
                throw new IllegalStateException(
                        "server '" + server_name + "' is not connected (status: " + state.status() + ")");
            }
            return List.copyOf(state.tools());
        }
    }

    public JsonNode call_tool(String server_name, String tool_name, JsonNode arguments) {
        McpServerState state;
        synchronized (lock) {
            state = servers.get(server_name);
            if (state == null) {
                throw new IllegalArgumentException("server '" + server_name + "' not found");
            }
            if (state.status() != ConnectionStatus.CONNECTED) {
                throw new IllegalStateException(
                        "server '" + server_name + "' is not connected (status: " + state.status() + ")");
            }
            boolean known = state.tools().stream().anyMatch(t -> t.name().equals(tool_name));
            if (!known) {
                throw new IllegalArgumentException(
                        "tool '" + tool_name + "' not found on server '" + server_name + "'");
            }
        }
        McpServerManager manager = manager_ref.get();
        if (manager == null) {
            throw new IllegalStateException("MCP server manager is not configured");
        }
        // Discover before calling so freshly-spawned managers route correctly.
        manager.discover_tools();
        JsonRpcResponse response = manager.call_tool(
                Mcp.mcp_tool_name(server_name, tool_name),
                (arguments == null || arguments.isNull()) ? null : arguments);
        if (response.isError()) {
            throw new IllegalStateException("MCP server returned JSON-RPC error for tools/call: "
                    + response.error().message() + " (" + response.error().code() + ")");
        }
        if (response.result() == null) {
            throw new IllegalStateException("MCP server returned no result for tools/call");
        }
        return response.result();
    }

    public void set_auth_status(String server_name, ConnectionStatus status) {
        synchronized (lock) {
            McpServerState state = servers.get(server_name);
            if (state == null) {
                throw new IllegalArgumentException("server '" + server_name + "' not found");
            }
            servers.put(
                    server_name,
                    new McpServerState(
                            state.server_name(),
                            status,
                            state.tools(),
                            state.resources(),
                            state.server_info(),
                            state.error_message()));
        }
    }

    public Optional<McpServerState> disconnect(String server_name) {
        synchronized (lock) {
            return Optional.ofNullable(servers.remove(server_name));
        }
    }

    public int size() {
        synchronized (lock) {
            return servers.size();
        }
    }

    public boolean is_empty() {
        return size() == 0;
    }
}
