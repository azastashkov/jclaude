package org.jclaude.runtime.mcp.jsonrpc.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SseTransportTest {

    private HttpServer server;

    @BeforeEach
    void start_server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                ObjectNode result = JsonRpcCodec.mapper().createObjectNode().put("greeting", "hi");
                JsonRpcResponse response = JsonRpcResponse.success(JsonRpcId.of(1L), result);
                String json = JsonRpcCodec.encode(response);
                String frame = "data: " + json + "\n\n";
                byte[] payload = frame.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "text/event-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                    out.flush();
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
    void posts_request_and_correlates_sse_response_by_id() throws Exception {
        try (SseTransport transport = new SseTransport(url(), Map.of(), Duration.ofSeconds(5))) {
            JsonRpcResponse response = transport
                    .send(new JsonRpcRequest(JsonRpcId.of(1L), "greet", null))
                    .get();

            assertThat(response.id()).isEqualTo(JsonRpcId.of(1L));
            assertThat(response.result().get("greeting").asText()).isEqualTo("hi");
        }
    }
}
