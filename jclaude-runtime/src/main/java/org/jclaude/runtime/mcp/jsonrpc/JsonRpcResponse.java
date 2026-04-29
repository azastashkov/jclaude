package org.jclaude.runtime.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 response envelope. Either {@code result} or {@code error} is present, never both,
 * mirroring the JSON-RPC 2.0 specification §5.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, JsonRpcId id, JsonNode result, JsonRpcError error) {

    @JsonCreator
    public static JsonRpcResponse fromJson(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonRpcId id,
            @JsonProperty("result") JsonNode result,
            @JsonProperty("error") JsonRpcError error) {
        return new JsonRpcResponse(
                jsonrpc == null ? JsonRpcRequest.VERSION : jsonrpc,
                id == null ? JsonRpcId.nullId() : id,
                result,
                error);
    }

    @JsonIgnore
    public boolean isError() {
        return error != null;
    }

    public static JsonRpcResponse success(JsonRpcId id, JsonNode result) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, result, null);
    }

    public static JsonRpcResponse failure(JsonRpcId id, JsonRpcError error) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, null, error);
    }
}
