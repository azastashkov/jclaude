package org.jclaude.runtime.mcp.jsonrpc.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * SSE JSON-RPC transport. POSTs each request to the endpoint and reads server-sent events
 * (text/event-stream) from the response body, correlating events back to outstanding requests
 * via the JSON-RPC {@code id}. Mirrors the {@code McpClientTransport::Sse} variant in the Rust
 * client.
 *
 * <p>This implementation uses a small built-in line-based SSE parser rather than wiring in
 * {@code org.jclaude.api.sse.SseStreamReader} to avoid a dependency on Anthropic-specific
 * stream-event types.
 */
public final class SseTransport implements Transport {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http_client;
    private final Duration request_timeout;
    private final Map<JsonRpcId, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private final ExecutorService io_executor = Executors.newVirtualThreadPerTaskExecutor();

    public SseTransport(String url, Map<String, String> headers) {
        this(url, headers, Duration.ofSeconds(60));
    }

    public SseTransport(String url, Map<String, String> headers, Duration request_timeout) {
        this.endpoint = URI.create(Objects.requireNonNull(url, "url"));
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        this.request_timeout = Objects.requireNonNull(request_timeout, "request_timeout");
        this.http_client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        byte[] body = JsonRpcCodec.encodeBytes(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(request_timeout)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        http_client
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        completePending(request.id(), error);
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        completePending(
                                request.id(),
                                new IOException("SSE transport returned status " + response.statusCode()));
                        return;
                    }
                    io_executor.submit(() -> drainStream(response.body()));
                });
        return future;
    }

    private void drainStream(InputStream body) {
        try (body) {
            StringBuilder buffer = new StringBuilder();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = body.read(chunk)) != -1) {
                buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                int boundary;
                while ((boundary = nextBoundary(buffer)) >= 0) {
                    int end = boundary;
                    int separator_length = endSeparatorLength(buffer, boundary);
                    String frame = buffer.substring(0, end);
                    buffer.delete(0, end + separator_length);
                    handleFrame(frame);
                }
            }
        } catch (IOException error) {
            failAllPending(error);
        }
    }

    private static int nextBoundary(CharSequence buffer) {
        int len = buffer.length();
        for (int i = 0; i < len - 1; i++) {
            if (buffer.charAt(i) == '\n' && buffer.charAt(i + 1) == '\n') {
                return i;
            }
            if (i < len - 3
                    && buffer.charAt(i) == '\r'
                    && buffer.charAt(i + 1) == '\n'
                    && buffer.charAt(i + 2) == '\r'
                    && buffer.charAt(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int endSeparatorLength(CharSequence buffer, int boundary) {
        if (boundary + 3 < buffer.length()
                && buffer.charAt(boundary) == '\r'
                && buffer.charAt(boundary + 1) == '\n'
                && buffer.charAt(boundary + 2) == '\r'
                && buffer.charAt(boundary + 3) == '\n') {
            return 4;
        }
        return 2;
    }

    private void handleFrame(String frame) {
        List<String> dataLines = new ArrayList<>();
        for (String raw : frame.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }
        if (dataLines.isEmpty()) {
            return;
        }
        String payload = String.join("\n", dataLines);
        if ("[DONE]".equals(payload)) {
            return;
        }
        JsonRpcResponse response;
        try {
            response = JsonRpcCodec.decodeResponse(payload);
        } catch (RuntimeException error) {
            failAllPending(new IOException("malformed JSON-RPC SSE frame: " + error.getMessage(), error));
            return;
        }
        completePending(response.id(), response);
    }

    private void completePending(JsonRpcId id, JsonRpcResponse response) {
        CompletableFuture<JsonRpcResponse> waiter = pending.remove(id);
        if (waiter != null) {
            waiter.complete(response);
        }
    }

    private void completePending(JsonRpcId id, Throwable error) {
        CompletableFuture<JsonRpcResponse> waiter = pending.remove(id);
        if (waiter != null) {
            waiter.completeExceptionally(error);
        }
    }

    private void failAllPending(Throwable error) {
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(error);
        }
        pending.clear();
    }

    @Override
    public void close() {
        failAllPending(new IOException("SSE transport closed"));
        io_executor.shutdownNow();
    }
}
