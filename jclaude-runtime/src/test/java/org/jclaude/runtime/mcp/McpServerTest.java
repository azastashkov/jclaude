package org.jclaude.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcError;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.Test;

class McpServerTest {

    @Test
    void dispatch_initialize_returns_server_info() {
        McpServer server = new McpServer(
                new McpServer.McpServerSpecRegistration("test", "9.9.9", List.of()),
                (name, args) -> McpServer.ToolHandlerResult.ok(""));
        JsonRpcRequest request = new JsonRpcRequest(JsonRpcId.of(1L), "initialize", null);

        JsonRpcResponse response = server.dispatch(request);

        assertThat(response.id()).isEqualTo(JsonRpcId.of(1L));
        assertThat(response.error()).isNull();
        JsonNode result = response.result();
        assertThat(result.get("protocolVersion").asText()).isEqualTo(McpServer.MCP_SERVER_PROTOCOL_VERSION);
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("test");
        assertThat(result.get("serverInfo").get("version").asText()).isEqualTo("9.9.9");
    }

    @Test
    void dispatch_tools_list_returns_registered_tools() {
        ObjectNode schema = JsonRpcCodec.mapper().createObjectNode().put("type", "object");
        McpTool tool = McpTool.of("echo", "Echo", schema);
        McpServer server = new McpServer(
                new McpServer.McpServerSpecRegistration("test", "0.0.0", List.of(tool)),
                (name, args) -> McpServer.ToolHandlerResult.ok(""));

        JsonRpcResponse response = server.dispatch(new JsonRpcRequest(JsonRpcId.of(2L), "tools/list", null));

        assertThat(response.error()).isNull();
        assertThat(response.result().get("tools").get(0).get("name").asText()).isEqualTo("echo");
    }

    @Test
    void dispatch_tools_call_wraps_handler_output() {
        McpServer server = new McpServer(
                new McpServer.McpServerSpecRegistration("test", "0.0.0", List.of()),
                (name, args) -> McpServer.ToolHandlerResult.ok("called " + name + " with " + args));
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", "echo");
        params.set("arguments", JsonRpcCodec.mapper().createObjectNode().put("text", "hi"));

        JsonRpcResponse response = server.dispatch(new JsonRpcRequest(JsonRpcId.of(3L), "tools/call", params));

        assertThat(response.error()).isNull();
        assertThat(response.result().get("isError").asBoolean()).isFalse();
        assertThat(response.result().get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(response.result().get("content").get(0).get("text").asText()).startsWith("called echo");
    }

    @Test
    void dispatch_tools_call_surfaces_handler_error() {
        McpServer server = new McpServer(
                new McpServer.McpServerSpecRegistration("test", "0.0.0", List.of()),
                (name, args) -> McpServer.ToolHandlerResult.err("boom"));
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode().put("name", "broken");

        JsonRpcResponse response = server.dispatch(new JsonRpcRequest(JsonRpcId.of(4L), "tools/call", params));

        assertThat(response.result().get("isError").asBoolean()).isTrue();
        assertThat(response.result().get("content").get(0).get("text").asText()).isEqualTo("boom");
    }

    @Test
    void dispatch_unknown_method_returns_method_not_found() {
        McpServer server = new McpServer(
                new McpServer.McpServerSpecRegistration("test", "0.0.0", List.of()),
                (name, args) -> McpServer.ToolHandlerResult.ok(""));

        JsonRpcResponse response = server.dispatch(new JsonRpcRequest(JsonRpcId.of(5L), "nonsense", null));

        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND);
    }
}
