package org.jclaude.runtime.mcp.jsonrpc.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * HTTP JSON-RPC transport. Posts each request to {@code base_url} and parses the body as a
 * JSON-RPC response. Mirrors the {@code McpClientTransport::Http} variant in the Rust client
 * (see {@code mcp_client.rs}). Connection pooling, redirects and TLS are handled by the JDK
 * built-in {@link HttpClient}.
 */
public final class HttpTransport implements Transport {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http_client;
    private final Duration timeout;

    public HttpTransport(String url, Map<String, String> headers) {
        this(url, headers, Duration.ofSeconds(60));
    }

    public HttpTransport(String url, Map<String, String> headers, Duration timeout) {
        this.endpoint = URI.create(Objects.requireNonNull(url, "url"));
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http_client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        byte[] body = JsonRpcCodec.encodeBytes(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return http_client
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int code = response.statusCode();
                    if (code < 200 || code >= 300) {
                        throw new java.io.UncheckedIOException(
                                new java.io.IOException("HTTP transport returned status " + code));
                    }
                    return JsonRpcCodec.decodeResponse(response.body());
                });
    }

    public URI endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        // HttpClient has no explicit close; rely on GC. The virtual-thread executor is also
        // managed implicitly by the JDK.
    }
}
