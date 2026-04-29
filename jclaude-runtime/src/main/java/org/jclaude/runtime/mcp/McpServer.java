package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcError;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * Minimal in-process MCP server. Mirrors the dispatch loop of {@code mcp_server.rs}: handles
 * {@code initialize}, {@code tools/list}, {@code tools/call} and surfaces JSON-RPC errors for
 * everything else.
 *
 * <p>Unlike the Rust reference implementation this server exposes a pure
 * {@link #dispatch(JsonRpcRequest)} entry point rather than running the stdio loop directly —
 * the loop can be assembled on top by the caller using a {@link org.jclaude.runtime.mcp.jsonrpc.transport.StdioTransport}
 * or any other reader/writer pair.
 */
public final class McpServer {

    public static final String MCP_SERVER_PROTOCOL_VERSION = "2025-03-26";

    private final McpServerSpecRegistration spec;
    private final BiFunction<String, JsonNode, ToolHandlerResult> tool_handler;

    public McpServer(McpServerSpecRegistration spec, BiFunction<String, JsonNode, ToolHandlerResult> tool_handler) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.tool_handler = Objects.requireNonNull(tool_handler, "tool_handler");
    }

    /** Server-side tool registration record (separate from the client-side {@link McpServerSpec}). */
    public record McpServerSpecRegistration(String server_name, String server_version, List<McpTool> tools) {

        public McpServerSpecRegistration {
            Objects.requireNonNull(server_name, "server_name");
            Objects.requireNonNull(server_version, "server_version");
            Objects.requireNonNull(tools, "tools");
            tools = List.copyOf(tools);
        }
    }

    /**
     * Result returned by a tool handler. Mirrors the Rust {@code Result<String, String>}: a string
     * payload plus an optional error flag.
     */
    public record ToolHandlerResult(String text, boolean is_error) {

        public static ToolHandlerResult ok(String text) {
            return new ToolHandlerResult(text, false);
        }

        public static ToolHandlerResult err(String text) {
            return new ToolHandlerResult(text, true);
        }
    }

    public JsonRpcResponse dispatch(JsonRpcRequest request) {
        return switch (request.method()) {
            case "initialize" -> handleInitialize(request.id());
            case "tools/list" -> handleToolsList(request.id());
            case "tools/call" -> handleToolsCall(request.id(), request.params());
            default -> JsonRpcResponse.failure(
                    request.id(),
                    new JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "method not found: " + request.method()));
        };
    }

    private JsonRpcResponse handleInitialize(JsonRpcId id) {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode();
        result.put("protocolVersion", MCP_SERVER_PROTOCOL_VERSION);
        ObjectNode capabilities = JsonRpcCodec.mapper().createObjectNode();
        capabilities.set("tools", JsonRpcCodec.mapper().createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode server_info = JsonRpcCodec.mapper().createObjectNode();
        server_info.put("name", spec.server_name());
        server_info.put("version", spec.server_version());
        result.set("serverInfo", server_info);
        return JsonRpcResponse.success(id, result);
    }

    private JsonRpcResponse handleToolsList(JsonRpcId id) {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode();
        ArrayNode tools_array = JsonRpcCodec.mapper().createArrayNode();
        for (McpTool tool : spec.tools()) {
            tools_array.add(JsonRpcCodec.toNode(tool));
        }
        result.set("tools", tools_array);
        return JsonRpcResponse.success(id, result);
    }

    private JsonRpcResponse handleToolsCall(JsonRpcId id, JsonNode params) {
        if (params == null || params.isNull()) {
            return JsonRpcResponse.failure(
                    id, new JsonRpcError(JsonRpcError.INVALID_PARAMS, "missing params for tools/call"));
        }
        JsonNode name_node = params.get("name");
        if (name_node == null || !name_node.isTextual()) {
            return JsonRpcResponse.failure(
                    id, new JsonRpcError(JsonRpcError.INVALID_PARAMS, "invalid tools/call params: missing name"));
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null) {
            arguments = JsonRpcCodec.mapper().createObjectNode();
        }
        ToolHandlerResult outcome = tool_handler.apply(name_node.asText(), arguments);
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode();
        ArrayNode content = JsonRpcCodec.mapper().createArrayNode();
        ObjectNode block = JsonRpcCodec.mapper().createObjectNode();
        block.put("type", "text");
        block.put("text", outcome.text());
        content.add(block);
        result.set("content", content);
        result.put("isError", outcome.is_error());
        return JsonRpcResponse.success(id, result);
    }
}
