package org.jclaude.runtime.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * JSON-RPC 2.0 error object. Standard error codes follow the spec.
 *
 * <ul>
 *   <li>{@code -32700} Parse error
 *   <li>{@code -32600} Invalid request
 *   <li>{@code -32601} Method not found
 *   <li>{@code -32602} Invalid params
 *   <li>{@code -32603} Internal error
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, JsonNode data) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public JsonRpcError {
        Objects.requireNonNull(message, "message");
    }

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    @JsonCreator
    public static JsonRpcError fromJson(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") JsonNode data) {
        return new JsonRpcError(code, message, data);
    }
}
