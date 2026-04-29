package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcError;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.jclaude.runtime.mcp.jsonrpc.transport.Transport;

/**
 * High-level MCP client that drives the JSON-RPC handshake plus tool / resource discovery on top
 * of a {@link Transport}. Mirrors the lifecycle and method shapes from {@code mcp_stdio.rs} but
 * is transport-agnostic: caller passes any {@link Transport} (stdio / HTTP / SSE / WebSocket).
 */
public final class McpClient {

    public static final String PROTOCOL_VERSION = "2025-03-26";
    public static final String DEFAULT_CLIENT_NAME = "jclaude-runtime";
    public static final String DEFAULT_CLIENT_VERSION = "0.1.0";

    private final String server_name;
    private final Transport transport;
    private final AtomicLong next_id = new AtomicLong(1);
    private boolean initialized = false;

    public McpClient(String server_name, Transport transport) {
        this.server_name = server_name;
        this.transport = transport;
    }

    public String server_name() {
        return server_name;
    }

    public Transport transport() {
        return transport;
    }

    public synchronized JsonRpcResponse initialize(Duration timeout) {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", JsonRpcCodec.mapper().createObjectNode());
        ObjectNode client_info = JsonRpcCodec.mapper().createObjectNode();
        client_info.put("name", DEFAULT_CLIENT_NAME);
        client_info.put("version", DEFAULT_CLIENT_VERSION);
        params.set("clientInfo", client_info);
        JsonRpcResponse response = sendRequest("initialize", params, timeout);
        if (response.isError()) {
            throw new McpException.JsonRpcFailure(server_name, "initialize", response.error());
        }
        if (response.result() == null) {
            throw new McpException.InvalidResponse(server_name, "initialize", "missing result payload");
        }
        initialized = true;
        return response;
    }

    public List<McpTool> list_tools(Duration timeout) {
        ensureInitialized();
        List<McpTool> all = new ArrayList<>();
        String cursor = null;
        while (true) {
            ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonRpcResponse response = sendRequest("tools/list", params, timeout);
            if (response.isError()) {
                throw new McpException.JsonRpcFailure(server_name, "tools/list", response.error());
            }
            JsonNode result = response.result();
            if (result == null) {
                throw new McpException.InvalidResponse(server_name, "tools/list", "missing result payload");
            }
            JsonNode tools_node = result.get("tools");
            if (tools_node != null && tools_node.isArray()) {
                for (JsonNode tool_node : tools_node) {
                    try {
                        all.add(JsonRpcCodec.mapper().treeToValue(tool_node, McpTool.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                        throw new McpException.InvalidResponse(server_name, "tools/list", error.getMessage());
                    }
                }
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull()) {
                break;
            }
            cursor = next.asText();
        }
        return all;
    }

    public List<McpResource> list_resources(Duration timeout) {
        ensureInitialized();
        List<McpResource> all = new ArrayList<>();
        String cursor = null;
        while (true) {
            ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonRpcResponse response = sendRequest("resources/list", params, timeout);
            if (response.isError()) {
                throw new McpException.JsonRpcFailure(server_name, "resources/list", response.error());
            }
            JsonNode result = response.result();
            if (result == null) {
                throw new McpException.InvalidResponse(server_name, "resources/list", "missing result payload");
            }
            JsonNode resources_node = result.get("resources");
            if (resources_node != null && resources_node.isArray()) {
                for (JsonNode resource_node : resources_node) {
                    try {
                        all.add(JsonRpcCodec.mapper().treeToValue(resource_node, McpResource.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                        throw new McpException.InvalidResponse(server_name, "resources/list", error.getMessage());
                    }
                }
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull()) {
                break;
            }
            cursor = next.asText();
        }
        return all;
    }

    public JsonRpcResponse call_tool(String name, JsonNode arguments, Duration timeout) {
        ensureInitialized();
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", name);
        if (arguments != null && !arguments.isNull()) {
            params.set("arguments", arguments);
        }
        return sendRequest("tools/call", params, timeout);
    }

    public JsonNode read_resource(String uri, Duration timeout) {
        ensureInitialized();
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("uri", uri);
        JsonRpcResponse response = sendRequest("resources/read", params, timeout);
        if (response.isError()) {
            throw new McpException.JsonRpcFailure(server_name, "resources/read", response.error());
        }
        if (response.result() == null) {
            throw new McpException.InvalidResponse(server_name, "resources/read", "missing result payload");
        }
        return response.result();
    }

    public void close() {
        transport.close();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MCP client `" + server_name + "` has not been initialized");
        }
    }

    private JsonRpcResponse sendRequest(String method, JsonNode params, Duration timeout) {
        long id = next_id.getAndIncrement();
        JsonRpcRequest request = new JsonRpcRequest(JsonRpcId.of(id), method, params);
        try {
            JsonRpcResponse response = transport.send(request).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new McpException.InvalidResponse(server_name, method, "null response");
            }
            if (!response.id().equals(JsonRpcId.of(id))) {
                throw new McpException.InvalidResponse(
                        server_name, method, "mismatched id: expected " + id + " got " + response.id());
            }
            return response;
        } catch (TimeoutException error) {
            throw new McpException.TimeoutFailure(server_name, method, timeout.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpException.TransportFailure(server_name, method, error);
        } catch (ExecutionException | CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof McpException me) {
                throw me;
            }
            throw new McpException.TransportFailure(server_name, method, cause == null ? error : cause);
        }
    }

    /** Snapshot the next-id counter so tests can assert id allocation behaviour. */
    public long peek_next_id() {
        return next_id.get();
    }

    /** Construct a JSON-RPC error response (used by tests / fixtures). */
    public static JsonRpcResponse build_error_response(JsonRpcId id, JsonRpcError error) {
        return JsonRpcResponse.failure(id, error);
    }

    /** Wrap an arbitrary {@link JsonNode} payload as a successful response. */
    public static JsonRpcResponse build_success_response(JsonRpcId id, JsonNode result) {
        return JsonRpcResponse.success(id, result);
    }

    /** Convenience for creating an empty client info object node. */
    public static Optional<ObjectNode> empty_capabilities() {
        return Optional.of(JsonRpcCodec.mapper().createObjectNode());
    }
}
