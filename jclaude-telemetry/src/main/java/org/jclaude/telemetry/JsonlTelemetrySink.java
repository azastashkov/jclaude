package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Sink that appends one JSON object per line to a file. Mirrors Rust's JsonlTelemetrySink. */
public final class JsonlTelemetrySink implements TelemetrySink, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final BufferedWriter writer;

    public JsonlTelemetrySink(Path path) throws IOException {
        this.path = path;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        this.writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void record(TelemetryEvent event) {
        try {
            ObjectNode node = serialize(event);
            writer.write(MAPPER.writeValueAsString(node));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    static ObjectNode serialize(TelemetryEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", event.type_label());
        switch (event) {
            case TelemetryEvent.HttpRequestStarted e -> {
                node.put("session_id", e.session_id());
                node.put("attempt", e.attempt());
                node.put("method", e.method());
                node.put("path", e.path());
                if (!e.attributes().isEmpty()) {
                    node.set("attributes", mapToNode(e.attributes()));
                }
            }
            case TelemetryEvent.HttpRequestSucceeded e -> {
                node.put("session_id", e.session_id());
                node.put("attempt", e.attempt());
                node.put("method", e.method());
                node.put("path", e.path());
                node.put("status", e.status());
                e.request_id().ifPresent(rid -> node.put("request_id", rid));
                if (!e.attributes().isEmpty()) {
                    node.set("attributes", mapToNode(e.attributes()));
                }
            }
            case TelemetryEvent.HttpRequestFailed e -> {
                node.put("session_id", e.session_id());
                node.put("attempt", e.attempt());
                node.put("method", e.method());
                node.put("path", e.path());
                node.put("error", e.error());
                node.put("retryable", e.retryable());
                if (!e.attributes().isEmpty()) {
                    node.set("attributes", mapToNode(e.attributes()));
                }
            }
            case TelemetryEvent.Analytics a -> {
                AnalyticsEvent inner = a.event();
                node.put("namespace", inner.namespace());
                node.put("action", inner.action());
                if (!inner.properties().isEmpty()) {
                    node.set("properties", mapToNode(inner.properties()));
                }
            }
            case TelemetryEvent.SessionTrace s -> {
                SessionTraceRecord record = s.record();
                node.put("session_id", record.session_id());
                node.put("sequence", record.sequence());
                node.put("name", record.name());
                node.put("timestamp_ms", record.timestamp_ms());
                if (!record.attributes().isEmpty()) {
                    node.set("attributes", mapToNode(record.attributes()));
                }
            }
        }
        return node;
    }

    private static ObjectNode mapToNode(Map<String, JsonNode> map) {
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : map.entrySet()) {
            out.set(entry.getKey(), entry.getValue());
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static ArrayNode toArray(Iterable<JsonNode> values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (JsonNode v : values) {
            array.add(v);
        }
        return array;
    }
}
