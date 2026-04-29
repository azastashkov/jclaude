package org.jclaude.runtime.mcp.jsonrpc.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcId;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * WebSocket JSON-RPC transport. Establishes a single full-duplex WebSocket connection and
 * exchanges JSON frames; responses are correlated to requests via the JSON-RPC {@code id}.
 * Mirrors the {@code McpClientTransport::WebSocket} variant in the Rust client.
 */
public final class WebSocketTransport implements Transport {

    private final URI endpoint;
    private final WebSocket ws;
    private final Map<JsonRpcId, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();

    public WebSocketTransport(String url, Map<String, String> headers) {
        this(url, headers, Duration.ofSeconds(15));
    }

    public WebSocketTransport(String url, Map<String, String> headers, Duration connect_timeout) {
        this.endpoint = URI.create(Objects.requireNonNull(url, "url"));
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(connect_timeout, "connect_timeout");
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(connect_timeout)
                .build();
        WebSocket.Builder builder = client.newWebSocketBuilder();
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        try {
            this.ws = builder.buildAsync(endpoint, new Listener())
                    .get(connect_timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new java.io.UncheckedIOException(new IOException("failed to open WebSocket: " + endpoint, error));
        }
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        String text = JsonRpcCodec.encode(request);
        ws.sendText(text, true).whenComplete((ignored, error) -> {
            if (error != null) {
                CompletableFuture<JsonRpcResponse> waiter = pending.remove(request.id());
                if (waiter != null) {
                    waiter.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    @Override
    public void close() {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IOException("WebSocket transport closed"));
        }
        pending.clear();
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder accumulator = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            accumulator.append(data);
            if (last) {
                String text = accumulator.toString();
                accumulator.setLength(0);
                try {
                    JsonRpcResponse response = JsonRpcCodec.decodeResponse(text);
                    CompletableFuture<JsonRpcResponse> waiter = pending.remove(response.id());
                    if (waiter != null) {
                        waiter.complete(response);
                    }
                } catch (RuntimeException error) {
                    failAllPending(new IOException("malformed JSON-RPC WebSocket frame", error));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            accumulator.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (last) {
                String text = accumulator.toString();
                accumulator.setLength(0);
                try {
                    JsonRpcResponse response = JsonRpcCodec.decodeResponse(text);
                    CompletableFuture<JsonRpcResponse> waiter = pending.remove(response.id());
                    if (waiter != null) {
                        waiter.complete(response);
                    }
                } catch (RuntimeException error) {
                    failAllPending(new IOException("malformed JSON-RPC WebSocket binary frame", error));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failAllPending(new IOException("WebSocket closed: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failAllPending(error);
        }

        private void failAllPending(Throwable error) {
            for (var entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(error);
            }
            pending.clear();
        }
    }
}
