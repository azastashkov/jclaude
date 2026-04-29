package org.jclaude.runtime.mcp.jsonrpc.transport;

import java.util.concurrent.CompletableFuture;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcRequest;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;

/**
 * Sealed JSON-RPC transport abstraction. Each implementation handles the wire framing for one
 * MCP transport variant: stdio (Content-Length framed), HTTP (one POST per request), SSE
 * (POST + server-sent stream), and WebSocket (full-duplex frames).
 *
 * <p>{@link #send(JsonRpcRequest)} returns a future that completes with the matching response
 * for the request's {@code id}; for transports where responses can be pushed, the future
 * tracks the correlation server-side.
 */
public sealed interface Transport extends AutoCloseable
        permits StdioTransport, HttpTransport, SseTransport, WebSocketTransport {

    CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request);

    @Override
    void close();
}
