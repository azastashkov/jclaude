package org.jclaude.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.junit.jupiter.api.Test;

class McpToolBridgeTest {

    @Test
    void registers_and_retrieves_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "test-server",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("greet", Optional.of("Greet someone"), Optional.empty())),
                List.of(new McpToolBridge.McpResourceInfo(
                        "res://data", "Data", Optional.empty(), Optional.of("application/json"))),
                Optional.of("TestServer v1.0"));

        Optional<McpToolBridge.McpServerState> server = registry.get_server("test-server");
        assertThat(server).isPresent();
        assertThat(server.get().status()).isEqualTo(McpToolBridge.ConnectionStatus.CONNECTED);
        assertThat(server.get().tools()).hasSize(1);
        assertThat(server.get().resources()).hasSize(1);
    }

    @Test
    void lists_resources_from_connected_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(),
                List.of(new McpToolBridge.McpResourceInfo("res://alpha", "Alpha", Optional.empty(), Optional.empty())),
                Optional.empty());

        List<McpToolBridge.McpResourceInfo> resources = registry.list_resources("srv");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).uri()).isEqualTo("res://alpha");
    }

    @Test
    void rejects_resource_listing_for_disconnected_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv", McpToolBridge.ConnectionStatus.DISCONNECTED, List.of(), List.of(), Optional.empty());

        assertThatThrownBy(() -> registry.list_resources("srv")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reads_specific_resource() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(),
                List.of(new McpToolBridge.McpResourceInfo(
                        "res://data", "Data", Optional.of("Test data"), Optional.of("text/plain"))),
                Optional.empty());

        McpToolBridge.McpResourceInfo resource = registry.read_resource("srv", "res://data");
        assertThat(resource.name()).isEqualTo("Data");

        assertThatThrownBy(() -> registry.read_resource("srv", "res://missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_connected_server_without_manager_when_calling_tool_then_it_errors() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("greet", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());

        ObjectNode args = JsonRpcCodec.mapper().createObjectNode().put("name", "world");
        assertThatThrownBy(() -> registry.call_tool("srv", "greet", args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP server manager is not configured");

        assertThatThrownBy(() -> registry.call_tool(
                        "srv", "missing", JsonRpcCodec.mapper().createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_tool_call_on_disconnected_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.AUTH_REQUIRED,
                List.of(new McpToolBridge.McpToolInfo("greet", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());

        assertThatThrownBy(() ->
                        registry.call_tool("srv", "greet", JsonRpcCodec.mapper().createObjectNode()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sets_auth_and_disconnects() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv", McpToolBridge.ConnectionStatus.AUTH_REQUIRED, List.of(), List.of(), Optional.empty());
        registry.set_auth_status("srv", McpToolBridge.ConnectionStatus.CONNECTED);
        assertThat(registry.get_server("srv").orElseThrow().status())
                .isEqualTo(McpToolBridge.ConnectionStatus.CONNECTED);

        Optional<McpToolBridge.McpServerState> removed = registry.disconnect("srv");
        assertThat(removed).isPresent();
        assertThat(registry.is_empty()).isTrue();
    }

    @Test
    void rejects_operations_on_missing_server() {
        McpToolBridge registry = new McpToolBridge();
        assertThatThrownBy(() -> registry.list_resources("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.read_resource("missing", "uri")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list_tools("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.set_auth_status("missing", McpToolBridge.ConnectionStatus.CONNECTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mcp_connection_status_display_all_variants() {
        assertThat(McpToolBridge.ConnectionStatus.DISCONNECTED.toString()).isEqualTo("disconnected");
        assertThat(McpToolBridge.ConnectionStatus.CONNECTING.toString()).isEqualTo("connecting");
        assertThat(McpToolBridge.ConnectionStatus.CONNECTED.toString()).isEqualTo("connected");
        assertThat(McpToolBridge.ConnectionStatus.AUTH_REQUIRED.toString()).isEqualTo("auth_required");
        assertThat(McpToolBridge.ConnectionStatus.ERROR.toString()).isEqualTo("error");
    }

    @Test
    void list_servers_returns_all_registered() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "alpha", McpToolBridge.ConnectionStatus.CONNECTED, List.of(), List.of(), Optional.empty());
        registry.register_server(
                "beta", McpToolBridge.ConnectionStatus.CONNECTING, List.of(), List.of(), Optional.empty());

        List<McpToolBridge.McpServerState> servers = registry.list_servers();
        assertThat(servers).hasSize(2);
        assertThat(servers.stream().anyMatch(s -> s.server_name().equals("alpha")))
                .isTrue();
        assertThat(servers.stream().anyMatch(s -> s.server_name().equals("beta")))
                .isTrue();
    }

    @Test
    void list_tools_from_connected_server() {
        McpToolBridge registry = new McpToolBridge();
        ObjectNode schema = JsonRpcCodec.mapper().createObjectNode().put("type", "object");
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo(
                        "inspect", Optional.of("Inspect data"), Optional.<JsonNode>of(schema))),
                List.of(),
                Optional.empty());

        List<McpToolBridge.McpToolInfo> tools = registry.list_tools("srv");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("inspect");
    }

    @Test
    void list_tools_rejects_disconnected_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv", McpToolBridge.ConnectionStatus.AUTH_REQUIRED, List.of(), List.of(), Optional.empty());

        assertThatThrownBy(() -> registry.list_tools("srv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected")
                .hasMessageContaining("auth_required");
    }

    @Test
    void list_tools_rejects_missing_server() {
        McpToolBridge registry = new McpToolBridge();
        assertThatThrownBy(() -> registry.list_tools("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server 'missing' not found");
    }

    @Test
    void get_server_returns_none_for_missing() {
        McpToolBridge registry = new McpToolBridge();
        assertThat(registry.get_server("missing")).isEmpty();
    }

    @Test
    void upsert_overwrites_existing_server() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "srv", McpToolBridge.ConnectionStatus.CONNECTING, List.of(), List.of(), Optional.empty());
        registry.register_server(
                "srv",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("inspect", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.of("Inspector"));

        McpToolBridge.McpServerState state = registry.get_server("srv").orElseThrow();
        assertThat(state.status()).isEqualTo(McpToolBridge.ConnectionStatus.CONNECTED);
        assertThat(state.tools()).hasSize(1);
        assertThat(state.server_info()).contains("Inspector");
    }

    @Test
    void disconnect_missing_returns_none() {
        McpToolBridge registry = new McpToolBridge();
        assertThat(registry.disconnect("missing")).isEmpty();
    }

    @Test
    void len_and_is_empty_transitions() {
        McpToolBridge registry = new McpToolBridge();
        registry.register_server(
                "alpha", McpToolBridge.ConnectionStatus.CONNECTED, List.of(), List.of(), Optional.empty());
        registry.register_server(
                "beta", McpToolBridge.ConnectionStatus.CONNECTED, List.of(), List.of(), Optional.empty());
        int after_create = registry.size();
        registry.disconnect("alpha");
        int after_first_remove = registry.size();
        registry.disconnect("beta");

        assertThat(after_create).isEqualTo(2);
        assertThat(after_first_remove).isEqualTo(1);
        assertThat(registry.size()).isZero();
        assertThat(registry.is_empty()).isTrue();
    }
}
