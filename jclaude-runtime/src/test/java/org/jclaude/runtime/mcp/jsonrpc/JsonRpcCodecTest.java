package org.jclaude.runtime.mcp.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JsonRpcCodecTest {

    @Test
    void encode_request_emits_jsonrpc_2_0_envelope() {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode().put("protocolVersion", "2025-03-26");
        JsonRpcRequest request = new JsonRpcRequest(JsonRpcId.of(7L), "initialize", params);

        String json = JsonRpcCodec.encode(request);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"id\":7");
        assertThat(json).contains("\"method\":\"initialize\"");
        assertThat(json).contains("\"protocolVersion\":\"2025-03-26\"");
    }

    @Test
    void decode_response_round_trips_result_payload() {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode().put("foo", "bar");
        JsonRpcResponse original = JsonRpcResponse.success(JsonRpcId.of("abc"), result);

        String json = JsonRpcCodec.encode(original);
        JsonRpcResponse decoded = JsonRpcCodec.decodeResponse(json);

        assertThat(decoded.id()).isEqualTo(JsonRpcId.of("abc"));
        assertThat(decoded.result().get("foo").asText()).isEqualTo("bar");
        assertThat(decoded.isError()).isFalse();
    }

    @Test
    void decode_response_round_trips_error_payload() {
        JsonRpcError error = new JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "no such method");
        JsonRpcResponse original = JsonRpcResponse.failure(JsonRpcId.of(1L), error);

        String json = JsonRpcCodec.encode(original);
        JsonRpcResponse decoded = JsonRpcCodec.decodeResponse(json);

        assertThat(decoded.isError()).isTrue();
        assertThat(decoded.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND);
        assertThat(decoded.error().message()).isEqualTo("no such method");
    }

    @Test
    void json_rpc_id_distinguishes_number_string_and_null() {
        JsonRpcId number = JsonRpcId.of(42L);
        JsonRpcId text = JsonRpcId.of("call-1");
        JsonRpcId nullId = JsonRpcId.nullId();

        assertThat(number).isInstanceOf(JsonRpcId.Number.class);
        assertThat(text).isInstanceOf(JsonRpcId.Text.class);
        assertThat(nullId).isInstanceOf(JsonRpcId.Null.class);
    }

    @Test
    void decode_request_accepts_string_id() {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"method\":\"ping\"}";
        JsonRpcRequest request = JsonRpcCodec.decodeRequest(wire);

        assertThat(request.id()).isEqualTo(JsonRpcId.of("call-1"));
        assertThat(request.method()).isEqualTo("ping");
    }

    @Test
    void decode_response_accepts_null_id_for_parse_errors() {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}";
        JsonRpcResponse response = JsonRpcCodec.decodeResponse(wire);

        assertThat(response.id()).isInstanceOf(JsonRpcId.Null.class);
        assertThat(response.error().code()).isEqualTo(JsonRpcError.PARSE_ERROR);
    }

    @Test
    void to_node_round_trips_through_pojo() {
        record Sample(String foo, int count) {}
        JsonNode node = JsonRpcCodec.toNode(new Sample("bar", 9));

        assertThat(node.get("foo").asText()).isEqualTo("bar");
        assertThat(node.get("count").asInt()).isEqualTo(9);
    }
}
