package org.jclaude.runtime.mcp.jsonrpc.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

    private HttpServer server;
    private final List<String> request_bodies = new ArrayList<>();
    private final AtomicReference<String> next_response = new AtomicReference<>();

    @BeforeEach
    void start_server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                request_bodies.add(new String(body, StandardCharsets.UTF_8));
                String response = next_response.get();
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop_server() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Test
    void posts_request_and_decodes_json_response() throws Exception {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode().put("ok", true);
        next_response.set(JsonRpcCodec.encode(JsonRpcResponse.success(JsonRpcId.of(1L), result)));

        try (HttpTransport transport = new HttpTransport(url(), Map.of("x-extra", "value"))) {
            JsonRpcRequest request = new JsonRpcRequest(
                    JsonRpcId.of(1L),
                    "ping",
                    JsonRpcCodec.mapper().createObjectNode().put("hello", "world"));
            JsonRpcResponse response = transport.send(request).get();

            assertThat(response.id()).isEqualTo(JsonRpcId.of(1L));
            assertThat(response.result().get("ok").asBoolean()).isTrue();

            assertThat(request_bodies).hasSize(1);
            JsonNode capturedRequest = JsonRpcCodec.mapper().readTree(request_bodies.get(0));
            assertThat(capturedRequest.get("method").asText()).isEqualTo("ping");
            assertThat(capturedRequest.get("params").get("hello").asText()).isEqualTo("world");
        }
    }

    @Test
    void surfaces_jsonrpc_error_response() throws Exception {
        next_response.set(JsonRpcCodec.encode(JsonRpcResponse.failure(
                JsonRpcId.of(2L),
                new org.jclaude.runtime.mcp.jsonrpc.JsonRpcError(
                        org.jclaude.runtime.mcp.jsonrpc.JsonRpcError.METHOD_NOT_FOUND, "no such method"))));

        try (HttpTransport transport = new HttpTransport(url(), Map.of())) {
            JsonRpcResponse response = transport
                    .send(new JsonRpcRequest(JsonRpcId.of(2L), "missing", null))
                    .get();

            assertThat(response.isError()).isTrue();
            assertThat(response.error().code())
                    .isEqualTo(org.jclaude.runtime.mcp.jsonrpc.JsonRpcError.METHOD_NOT_FOUND);
        }
    }
}
