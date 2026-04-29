package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stdio JSON-RPC LSP client. Spawns the LSP server via {@link ProcessBuilder} and exchanges
 * Content-Length framed JSON-RPC messages over stdio.
 */
public final class LspClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final Thread reader_thread;
    private final AtomicLong next_id = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private LspClient(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();
        this.reader_thread =
                Thread.ofVirtual().name("lsp-reader-" + process.pid()).start(this::read_loop);
    }

    public static LspClient spawn(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        return new LspClient(p);
    }

    public static LspClient spawn(List<String> command, Map<String, String> environment) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(environment);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        return new LspClient(p);
    }

    public CompletableFuture<JsonNode> request(String method, Object params) {
        long id = next_id.getAndIncrement();
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", JSON.valueToTree(params));
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            send(message);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public void notify_method(String method, Object params) throws IOException {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", JSON.valueToTree(params));
        }
        send(message);
    }

    /** Initialize handshake with rootUri and basic capabilities. */
    public CompletableFuture<JsonNode> initialize(String root_uri) {
        ObjectNode params = JSON.createObjectNode();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", root_uri);
        params.set("capabilities", JSON.createObjectNode());
        return request("initialize", params);
    }

    public void initialized() throws IOException {
        notify_method("initialized", JSON.createObjectNode());
    }

    public CompletableFuture<JsonNode> shutdown() {
        return request("shutdown", null);
    }

    public void exit() throws IOException {
        notify_method("exit", null);
    }

    /** Convenience wrapper for textDocument/hover. */
    public CompletableFuture<JsonNode> hover(String uri, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        ObjectNode pos = params.putObject("position");
        pos.put("line", line);
        pos.put("character", character);
        return request("textDocument/hover", params);
    }

    public CompletableFuture<JsonNode> definition(String uri, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        ObjectNode pos = params.putObject("position");
        pos.put("line", line);
        pos.put("character", character);
        return request("textDocument/definition", params);
    }

    public CompletableFuture<JsonNode> references(String uri, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        ObjectNode pos = params.putObject("position");
        pos.put("line", line);
        pos.put("character", character);
        ObjectNode ctx = params.putObject("context");
        ctx.put("includeDeclaration", true);
        return request("textDocument/references", params);
    }

    public CompletableFuture<JsonNode> completion(String uri, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        ObjectNode pos = params.putObject("position");
        pos.put("line", line);
        pos.put("character", character);
        return request("textDocument/completion", params);
    }

    public CompletableFuture<JsonNode> document_symbols(String uri) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        return request("textDocument/documentSymbol", params);
    }

    public CompletableFuture<JsonNode> formatting(String uri) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode doc = params.putObject("textDocument");
        doc.put("uri", uri);
        ObjectNode opts = params.putObject("options");
        opts.put("tabSize", 4);
        opts.put("insertSpaces", true);
        return request("textDocument/formatting", params);
    }

    public boolean is_alive() {
        return !closed && process.isAlive();
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("LSP client closed"));
        }
        pending.clear();
        reader_thread.interrupt();
    }

    private synchronized void send(JsonNode message) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(message);
        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        stdin.write(header.getBytes(StandardCharsets.US_ASCII));
        stdin.write(payload);
        stdin.flush();
    }

    private void read_loop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        try {
            while (!closed) {
                int content_length = -1;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        content_length = Integer.parseInt(
                                line.substring("content-length:".length()).trim());
                    }
                }
                if (line == null) {
                    return;
                }
                if (content_length < 0) {
                    continue;
                }
                char[] body = new char[content_length];
                int read = 0;
                while (read < content_length) {
                    int n = reader.read(body, read, content_length - read);
                    if (n < 0) {
                        return;
                    }
                    read += n;
                }
                String json = new String(body, 0, read);
                JsonNode parsed = JSON.readTree(json);
                if (parsed.has("id") && (parsed.has("result") || parsed.has("error"))) {
                    long id = parsed.get("id").asLong();
                    CompletableFuture<JsonNode> future = pending.remove(id);
                    if (future != null) {
                        if (parsed.has("error")) {
                            future.completeExceptionally(
                                    new RuntimeException(parsed.get("error").toString()));
                        } else {
                            future.complete(parsed.get("result"));
                        }
                    }
                }
                // Notifications (no id) are dropped here; the caller can use a registry-level dispatcher.
            }
        } catch (IOException e) {
            // Stream closed; complete all pending futures with the error.
            for (var entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(e);
            }
            pending.clear();
        } catch (RuntimeException e) {
            for (var entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(e);
            }
            pending.clear();
        }
    }

    /** Result of a parsed Content-Length header for a single LSP frame. */
    public record Frame(int content_length, Optional<String> content_type) {}
}
