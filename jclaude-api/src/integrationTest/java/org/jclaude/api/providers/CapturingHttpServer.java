package org.jclaude.api.providers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process HTTP server fixture used by the OpenAI-compat integration tests.
 * Each enqueued response is paired one-to-one with an incoming request.
 *
 * <p>Mirrors the hand-rolled tokio test server used by the Rust
 * {@code openai_compat_integration} suite — capturing the exact request shape
 * is more important than fidelity to a heavyweight mock framework.
 */
public final class CapturingHttpServer implements AutoCloseable {

    public record CapturedRequest(String method, String path, Map<String, String> headers, String body) {}

    public record StagedResponse(int status, Map<String, String> headers, byte[] body, String contentType) {

        public static StagedResponse json(int status, String body, Map<String, String> headers) {
            return new StagedResponse(status, headers, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        public static StagedResponse json(int status, String body) {
            return json(status, body, Map.of());
        }

        public static StagedResponse sse(String body, Map<String, String> headers) {
            return new StagedResponse(200, headers, body.getBytes(StandardCharsets.UTF_8), "text/event-stream");
        }
    }

    private final HttpServer server;
    private final List<CapturedRequest> captured = new ArrayList<>();
    private final Deque<StagedResponse> responses = new ArrayDeque<>();
    private final String base_url;

    public CapturingHttpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", new Handler());
        this.server.start();
        int port = server.getAddress().getPort();
        this.base_url = "http://127.0.0.1:" + port;
    }

    public String base_url() {
        return base_url;
    }

    public void enqueue(StagedResponse response) {
        synchronized (responses) {
            responses.addLast(response);
        }
    }

    public List<CapturedRequest> captured() {
        synchronized (captured) {
            return List.copyOf(captured);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private final class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders()
                        .forEach((name, values) ->
                                headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
                byte[] body_bytes;
                try (var stream = exchange.getRequestBody()) {
                    body_bytes = stream.readAllBytes();
                }
                String body = new String(body_bytes, StandardCharsets.UTF_8);
                CapturedRequest request = new CapturedRequest(
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
                synchronized (captured) {
                    captured.add(request);
                }
                StagedResponse staged;
                synchronized (responses) {
                    staged = responses.pollFirst();
                }
                if (staged == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                exchange.getResponseHeaders().add("content-type", staged.contentType());
                staged.headers()
                        .forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                exchange.sendResponseHeaders(staged.status(), staged.body().length == 0 ? -1 : staged.body().length);
                if (staged.body().length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(staged.body());
                    }
                }
            } finally {
                exchange.close();
            }
        }
    }
}
