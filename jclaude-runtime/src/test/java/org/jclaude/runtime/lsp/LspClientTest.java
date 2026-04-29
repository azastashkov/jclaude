package org.jclaude.runtime.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LspClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void content_length_framing_round_trip() throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", "initialize");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write_frame(out, message);

        String wire = out.toString(StandardCharsets.UTF_8);
        assertThat(wire).contains("Content-Length:");
        assertThat(wire).contains("\"jsonrpc\":\"2.0\"");
        assertThat(wire).contains("\"method\":\"initialize\"");
    }

    @Test
    void parses_nested_initialize_response() throws Exception {
        String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{\"textDocumentSync\":2}}}";

        JsonNode parsed = JSON.readTree(response);

        assertThat(parsed.get("id").asLong()).isEqualTo(1);
        assertThat(parsed.get("result")
                        .get("capabilities")
                        .get("textDocumentSync")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void error_response_carries_message() throws Exception {
        String response = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";

        JsonNode parsed = JSON.readTree(response);

        assertThat(parsed.has("error")).isTrue();
        assertThat(parsed.get("error").get("message").asText()).isEqualTo("Method not found");
    }

    private static void write_frame(OutputStream out, JsonNode message) throws Exception {
        byte[] payload = JSON.writeValueAsBytes(message);
        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }
}
