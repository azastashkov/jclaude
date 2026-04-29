package org.jclaude.tools.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.mcp.Mcp;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.runtime.mcp.McpToolBridge.McpResourceInfo;
import org.jclaude.runtime.mcp.McpToolBridge.McpServerState;
import org.jclaude.runtime.mcp.McpToolBridge.McpToolInfo;
import org.jclaude.tools.ToolResult;
import org.jclaude.tools.ToolSpec;

/**
 * Tools-layer adapter over {@link McpToolBridge}. Exposes the connected MCP servers' tools as
 * {@link ToolSpec} instances and dispatches calls back through the bridge. The runtime cannot
 * depend on {@code jclaude-tools}, so this thin adapter lives on the tools side and translates
 * between the runtime records and the tool surface.
 *
 * <p>Each MCP tool surfaces under its qualified name {@code mcp__<server>__<tool>} (computed by
 * {@link Mcp#mcp_tool_name(String, String)}) so the dispatcher can look it up directly.
 */
public final class McpToolBridgeAdapter {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private static final String DEFAULT_INPUT_SCHEMA_JSON =
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": true
            }
            """;

    private final McpToolBridge bridge;

    public McpToolBridgeAdapter(McpToolBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /** Underlying bridge — exposed so callers (e.g. tests / wiring code) can inspect server state. */
    public McpToolBridge bridge() {
        return bridge;
    }

    /**
     * Snapshots all tools from connected servers as {@link ToolSpec}s. Servers that are not
     * {@code CONNECTED} are skipped because their tools aren't dispatchable yet.
     */
    public List<ToolSpec> tool_specs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (McpServerState state : bridge.list_servers()) {
            if (state.status() != McpToolBridge.ConnectionStatus.CONNECTED) {
                continue;
            }
            for (McpToolInfo tool : state.tools()) {
                String qualified = Mcp.mcp_tool_name(state.server_name(), tool.name());
                String description = tool.description()
                        .orElse("MCP tool '" + tool.name() + "' on server '" + state.server_name() + "'.");
                JsonNode schema = tool.input_schema().orElseGet(McpToolBridgeAdapter::default_input_schema);
                specs.add(new ToolSpec(qualified, description, schema));
            }
        }
        return List.copyOf(specs);
    }

    /** Lists the resources advertised by {@code server_name}. Throws if the server is unknown. */
    public List<McpResourceInfo> list_resources(String server_name) {
        return bridge.list_resources(server_name);
    }

    /** Reads a resource descriptor by URI from {@code server_name}. */
    public McpResourceInfo read_resource(String server_name, String uri) {
        return bridge.read_resource(server_name, uri);
    }

    /** Returns the connected server names (in stable order). */
    public List<McpServerState> list_servers() {
        return bridge.list_servers();
    }

    /**
     * Dispatches a structured {@code MCP} tool call ({@code server} + {@code tool} + {@code
     * arguments}) and returns a JSON-encoded {@link ToolResult}.
     */
    public ToolResult call_structured(String server, String tool, JsonNode arguments) {
        try {
            JsonNode result = bridge.call_tool(server, tool, arguments);
            return ToolResult.text(serialize(result));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    /**
     * Dispatches a qualified {@code mcp__<server>__<tool>} name with the given arguments. Returns a
     * JSON-encoded {@link ToolResult} matching the underlying tools/call response.
     */
    public ToolResult call_qualified(String qualified_name, JsonNode arguments) {
        Optional<ServerTool> resolved = resolve_qualified(qualified_name);
        if (resolved.isEmpty()) {
            return ToolResult.error("MCP tool '" + qualified_name + "' is not registered with any connected server");
        }
        ServerTool route = resolved.get();
        return call_structured(route.server_name(), route.tool_name(), arguments);
    }

    /**
     * Returns true if any connected server has a tool whose qualified name matches {@code
     * qualified_name}.
     */
    public boolean handles(String qualified_name) {
        return resolve_qualified(qualified_name).isPresent();
    }

    private Optional<ServerTool> resolve_qualified(String qualified_name) {
        if (qualified_name == null || !qualified_name.startsWith("mcp__")) {
            return Optional.empty();
        }
        for (McpServerState state : bridge.list_servers()) {
            if (state.status() != McpToolBridge.ConnectionStatus.CONNECTED) {
                continue;
            }
            for (McpToolInfo tool : state.tools()) {
                if (Mcp.mcp_tool_name(state.server_name(), tool.name()).equals(qualified_name)) {
                    return Optional.of(new ServerTool(state.server_name(), tool.name()));
                }
            }
        }
        return Optional.empty();
    }

    private static JsonNode default_input_schema() {
        try {
            return MAPPER.readTree(DEFAULT_INPUT_SCHEMA_JSON);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid embedded MCP default schema", error);
        }
    }

    private static String serialize(JsonNode node) {
        try {
            return node == null ? "{}" : MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", "failed to serialise MCP result: " + error.getMessage());
            return err.toString();
        }
    }

    /** Internal routing record. */
    private record ServerTool(String server_name, String tool_name) {}
}
