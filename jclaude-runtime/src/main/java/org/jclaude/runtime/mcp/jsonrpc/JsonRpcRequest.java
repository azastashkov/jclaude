package org.jclaude.runtime.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * JSON-RPC 2.0 request envelope. Mirrors the Rust {@code JsonRpcRequest} record.
 *
 * <p>The {@code jsonrpc} field is always {@code "2.0"}. The {@code params} field is an opaque
 * {@link JsonNode} tree, deferring schema-specific shapes to the call site (initialize,
 * tools/list, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(String jsonrpc, JsonRpcId id, String method, JsonNode params) {

    /** Protocol version constant required by the JSON-RPC 2.0 spec. */
    public static final String VERSION = "2.0";

    public JsonRpcRequest {
        Objects.requireNonNull(jsonrpc, "jsonrpc");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
    }

    public JsonRpcRequest(JsonRpcId id, String method, JsonNode params) {
        this(VERSION, id, method, params);
    }

    @JsonCreator
    public static JsonRpcRequest fromJson(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonRpcId id,
            @JsonProperty("method") String method,
            @JsonProperty("params") JsonNode params) {
        return new JsonRpcRequest(
                jsonrpc == null ? VERSION : jsonrpc, id == null ? JsonRpcId.nullId() : id, method, params);
    }
}
