package org.jclaude.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.tools.ToolResult;
import org.jclaude.tools.ToolSpec;
import org.junit.jupiter.api.Test;

class McpToolBridgeAdapterTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    @Test
    void tool_specs_are_empty_when_no_servers_registered() {
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(new McpToolBridge());

        assertThat(adapter.tool_specs()).isEmpty();
    }

    @Test
    void tool_specs_skips_disconnected_servers() {
        McpToolBridge bridge = new McpToolBridge();
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.DISCONNECTED,
                List.of(new McpToolBridge.McpToolInfo("hello", Optional.of("d"), Optional.of(schema))),
                List.of(),
                Optional.empty());

        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);

        assertThat(adapter.tool_specs()).isEmpty();
        assertThat(adapter.handles("mcp__demo__hello")).isFalse();
    }

    @Test
    void tool_specs_includes_qualified_name_for_connected_server() {
        McpToolBridge bridge = new McpToolBridge();
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        bridge.register_server(
                "alpha",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("ping", Optional.of("ping the server"), Optional.of(schema))),
                List.of(),
                Optional.empty());

        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        List<ToolSpec> specs = adapter.tool_specs();

        assertThat(specs).hasSize(1);
        ToolSpec spec = specs.get(0);
        assertThat(spec.name()).isEqualTo("mcp__alpha__ping");
        assertThat(spec.description()).contains("ping");
        assertThat(spec.input_schema().get("type").asText()).isEqualTo("object");
        assertThat(adapter.handles("mcp__alpha__ping")).isTrue();
    }

    @Test
    void tool_specs_uses_default_schema_when_input_schema_missing() {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "beta",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("noop", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());

        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        ToolSpec spec = adapter.tool_specs().get(0);

        assertThat(spec.input_schema().get("type").asText()).isEqualTo("object");
        assertThat(spec.input_schema().get("additionalProperties").asBoolean()).isTrue();
    }

    @Test
    void call_qualified_returns_error_when_tool_unknown() {
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(new McpToolBridge());

        ToolResult result = adapter.call_qualified("mcp__missing__nope", MAPPER.createObjectNode());

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("not registered");
    }

    @Test
    void call_qualified_returns_error_for_non_mcp_prefix() {
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(new McpToolBridge());

        ToolResult result = adapter.call_qualified("not_an_mcp_tool", MAPPER.createObjectNode());

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void call_structured_propagates_errors_from_bridge() {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("ping", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());
        // No manager bound — call_tool should raise.
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);

        ToolResult result = adapter.call_structured("demo", "ping", MAPPER.createObjectNode());

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("manager");
    }

    @Test
    void list_servers_exposes_all_registered_servers_regardless_of_status() {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server("one", McpToolBridge.ConnectionStatus.CONNECTED, List.of(), List.of(), Optional.empty());
        bridge.register_server(
                "two", McpToolBridge.ConnectionStatus.DISCONNECTED, List.of(), List.of(), Optional.empty());

        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);

        assertThat(adapter.list_servers()).hasSize(2);
    }
}
