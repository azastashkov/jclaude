package org.jclaude.runtime.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Jackson-based JSON-RPC codec. The MCP wire format is camelCase (e.g. {@code protocolVersion},
 * {@code inputSchema}) so we deliberately do <strong>not</strong> apply the snake_case naming
 * strategy used by the rest of {@code JclaudeMappers}; {@link JsonNode} payloads are otherwise
 * passed through verbatim.
 */
public final class JsonRpcCodec {

    private static final ObjectMapper MAPPER = buildMapper();

    private JsonRpcCodec() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String encode(JsonRpcRequest request) {
        return write(request);
    }

    public static String encode(JsonRpcResponse response) {
        return write(response);
    }

    public static byte[] encodeBytes(JsonRpcRequest request) {
        return write(request).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] encodeBytes(JsonRpcResponse response) {
        return write(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static JsonRpcRequest decodeRequest(String json) {
        try {
            return MAPPER.readValue(json, JsonRpcRequest.class);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException("invalid JSON-RPC request: " + error.getMessage(), error));
        }
    }

    public static JsonRpcResponse decodeResponse(String json) {
        try {
            return MAPPER.readValue(json, JsonRpcResponse.class);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException("invalid JSON-RPC response: " + error.getMessage(), error));
        }
    }

    public static JsonRpcRequest decodeRequest(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, JsonRpcRequest.class);
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException("invalid JSON-RPC request: " + error.getMessage(), error));
        }
    }

    public static JsonRpcResponse decodeResponse(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, JsonRpcResponse.class);
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException("invalid JSON-RPC response: " + error.getMessage(), error));
        }
    }

    /** Convert any object into a {@link JsonNode} suitable for use in {@code params}. */
    public static JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException("failed to encode JSON-RPC value", error));
        }
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        // MCP spec uses camelCase wire format; do NOT install a snake_case strategy.
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
