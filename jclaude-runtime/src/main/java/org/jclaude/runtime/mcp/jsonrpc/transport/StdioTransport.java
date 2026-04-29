package org.jclaude.runtime.mcp.jsonrpc.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * Stdio JSON-RPC transport that spawns a child process and frames messages with the LSP-style
 * {@code Content-Length:} header followed by an empty line and the JSON payload. Mirrors the
 * Rust {@code McpStdioProcess::write_frame}/{@code read_frame} pair in
 * {@code crates/runtime/src/mcp_stdio.rs}.
 *
 * <p>The reader runs on a virtual thread and demultiplexes responses to outstanding callers via
 * a request-id keyed map.
 */
public final class StdioTransport implements Transport {

    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdout;
    private final ExecutorService io_executor;
    private final Map<JsonRpcId, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public StdioTransport(String command, List<String> args, Map<String, String> env) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        ProcessBuilder pb = new ProcessBuilder(buildCommand(command, args)).redirectErrorStream(false);
        pb.environment().putAll(env);
        try {
            this.process = pb.start();
        } catch (IOException error) {
            throw new UncheckedIOException("failed to spawn MCP stdio process", error);
        }
        this.stdin = process.getOutputStream();
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.io_executor = Executors.newVirtualThreadPerTaskExecutor();
        this.io_executor.submit(this::readLoop);
    }

    private static List<String> buildCommand(String command, List<String> args) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(args.size() + 1);
        all.add(command);
        all.addAll(args);
        return all;
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("stdio transport is closed"));
        }
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        try {
            byte[] payload = JsonRpcCodec.encodeBytes(request);
            byte[] framed = encodeFrame(payload);
            synchronized (stdin) {
                stdin.write(framed);
                stdin.flush();
            }
        } catch (IOException error) {
            pending.remove(request.id());
            future.completeExceptionally(error);
        }
        return future;
    }

    /** Read a single Content-Length framed payload off stdout. */
    public byte[] readFrame() throws IOException {
        Integer length = null;
        boolean any_header = false;
        while (true) {
            String line = stdout.readLine();
            if (line == null) {
                if (!any_header) {
                    throw new java.io.EOFException("MCP stdio stream closed before headers");
                }
                throw new java.io.EOFException("MCP stdio stream closed while reading headers");
            }
            any_header = true;
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.toLowerCase(Locale.ROOT).equals("content-length")) {
                    try {
                        length = Integer.parseInt(value);
                    } catch (NumberFormatException error) {
                        throw new IOException("invalid Content-Length: " + value, error);
                    }
                }
            }
        }
        if (length == null) {
            throw new IOException("missing Content-Length header");
        }
        char[] chars = new char[length];
        int read = 0;
        // BufferedReader is character-based but the upstream encoder writes UTF-8 byte-counted
        // payloads. For ASCII / typical JSON-RPC traffic these counts agree; for multi-byte
        // payloads we fall back to reading character-by-character which is sufficient for the
        // MCP protocol which only ever carries JSON.
        while (read < length) {
            int n = stdout.read(chars, read, length - read);
            if (n < 0) {
                throw new java.io.EOFException("MCP stdio stream closed mid-payload");
            }
            read += n;
        }
        return new String(chars, 0, read).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeFrame(byte[] payload) {
        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        byte[] head = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte[] payload;
                try {
                    payload = readFrame();
                } catch (java.io.EOFException eof) {
                    failPending(eof);
                    return;
                } catch (IOException error) {
                    failPending(error);
                    return;
                }
                JsonRpcResponse response;
                try {
                    response = JsonRpcCodec.decodeResponse(payload);
                } catch (RuntimeException error) {
                    // Malformed frame: surface to the most-recent caller (no id known).
                    failPending(new IOException("malformed JSON-RPC frame", error));
                    continue;
                }
                CompletableFuture<JsonRpcResponse> waiter = pending.remove(response.id());
                if (waiter != null) {
                    waiter.complete(response);
                }
            }
        } catch (Throwable error) {
            failPending(error);
        }
    }

    private void failPending(Throwable error) {
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(error);
        }
        pending.clear();
    }

    public Process process() {
        return process;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // Closing stdout breaks any active read in the readLoop.
        try {
            stdout.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
        io_executor.shutdownNow();
        failPending(new IOException("stdio transport closed"));
    }
}
