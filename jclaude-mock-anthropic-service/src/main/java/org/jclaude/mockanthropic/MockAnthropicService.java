package org.jclaude.mockanthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.MessageRequest;

/**
 * In-process mock of the Anthropic {@code /v1/messages} HTTP API. Selects deterministic responses
 * based on a {@code PARITY_SCENARIO:<name>} marker discovered in the most recent user text block.
 *
 * <p>Listens on {@code 127.0.0.1} with an ephemeral port unless an explicit bind address is
 * supplied. Records every request in {@link #captured_requests()} for downstream parity assertions.
 */
public final class MockAnthropicService implements AutoCloseable {

    private final HttpServer server;
    private final String base_url;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = JclaudeMappers.standard();
    private final ScenarioResponses responses = new ScenarioResponses(mapper);
    private final Executor executor;

    private MockAnthropicService(HttpServer server, String base_url, Executor executor) {
        this.server = server;
        this.base_url = base_url;
        this.executor = executor;
    }

    /** Bind to {@code 127.0.0.1:0} and start the server on an ephemeral port. */
    public static MockAnthropicService spawn() throws IOException {
        return spawn_on("127.0.0.1:0");
    }

    /** Bind to the given {@code host:port}. A port of 0 means an ephemeral port. */
    public static MockAnthropicService spawn_on(String bind_addr) throws IOException {
        String[] parts = bind_addr.split(":", 2);
        if (parts.length != 2) {
            throw new IOException("invalid bind address: " + bind_addr);
        }
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("invalid port in bind address: " + bind_addr, e);
        }
        InetSocketAddress address = new InetSocketAddress(parts[0], port);
        HttpServer server = HttpServer.create(address, 0);
        InetSocketAddress bound = server.getAddress();
        Executor exec = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-anthropic-service");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(exec);
        String base = "http://" + bound.getHostString() + ":" + bound.getPort();
        MockAnthropicService service = new MockAnthropicService(server, base, exec);
        server.createContext("/v1/messages", service.new MessagesHandler());
        server.createContext("/v1/messages/count_tokens", service.new CountTokensHandler());
        server.start();
        return service;
    }

    public String base_url() {
        return base_url;
    }

    public List<CapturedRequest> captured_requests() {
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private final class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    write_error(exchange, 405, "method not allowed");
                    return;
                }
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String body_text = new String(raw, StandardCharsets.UTF_8);
                MessageRequest request;
                JsonNode body_json;
                try {
                    body_json = mapper.readTree(body_text);
                    request = mapper.treeToValue(body_json, MessageRequest.class);
                } catch (Exception ex) {
                    write_error(exchange, 400, "invalid request body: " + ex.getMessage());
                    return;
                }
                Optional<Scenario> scenario = Scenario.detect(request);
                CapturedRequest captured = new CapturedRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestMethod(),
                        body_json,
                        request.stream(),
                        header_map(exchange),
                        scenario,
                        Instant.now());
                requests.add(captured);

                if (scenario.isEmpty()) {
                    write_error(exchange, 400, "missing parity scenario");
                    return;
                }

                if (request.stream()) {
                    String body = responses.build_stream_body(request, scenario.get());
                    write_streaming(exchange, body, scenario.get());
                } else {
                    String body = responses.build_message_response(request, scenario.get());
                    write_json(exchange, body, scenario.get());
                }
            }
        }
    }

    private final class CountTokensHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    write_error(exchange, 405, "method not allowed");
                    return;
                }
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String body_text = new String(raw, StandardCharsets.UTF_8);
                JsonNode body_json;
                try {
                    body_json = body_text.isEmpty() ? mapper.createObjectNode() : mapper.readTree(body_text);
                } catch (Exception ex) {
                    write_error(exchange, 400, "invalid count_tokens body: " + ex.getMessage());
                    return;
                }
                CapturedRequest captured = new CapturedRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestMethod(),
                        body_json,
                        false,
                        header_map(exchange),
                        Optional.empty(),
                        Instant.now());
                requests.add(captured);
                String body = "{\"input_tokens\":42}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    private void write_error(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void write_streaming(HttpExchange exchange, String body, Scenario scenario) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/event-stream");
        exchange.getResponseHeaders().add("x-request-id", ScenarioResponses.request_id_for(scenario));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void write_json(HttpExchange exchange, String body, Scenario scenario) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.getResponseHeaders().add("request-id", ScenarioResponses.request_id_for(scenario));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> header_map(HttpExchange exchange) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry :
                exchange.getRequestHeaders().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                result.put(key, values.get(0));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
