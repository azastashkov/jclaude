package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session tracer that records both raw {@link TelemetryEvent}s and a sequenced session-trace
 * mirror for offline replay. Default no-op variant available via {@link #noop(String)}.
 */
public class SessionTracer {

    private final String session_id;
    private final AtomicLong sequence;
    private final TelemetrySink sink;

    public SessionTracer(String session_id, TelemetrySink sink) {
        this.session_id = session_id;
        this.sink = sink == null ? TelemetrySink.noop() : sink;
        this.sequence = new AtomicLong(0);
    }

    public static SessionTracer noop(String session_id) {
        return new SessionTracer(session_id, TelemetrySink.noop());
    }

    public String session_id() {
        return session_id;
    }

    public TelemetrySink sink() {
        return sink;
    }

    public void record(String name, Map<String, JsonNode> attributes) {
        SessionTraceRecord record = new SessionTraceRecord(
                session_id, sequence.getAndIncrement(), name, System.currentTimeMillis(), attributes);
        sink.record(new TelemetryEvent.SessionTrace(record));
    }

    public void record_http_request_started(int attempt, String method, String path, Map<String, JsonNode> attributes) {
        Map<String, JsonNode> attrs = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        sink.record(new TelemetryEvent.HttpRequestStarted(session_id, attempt, method, path, attrs));
        record("http_request_started", merge_trace_fields(method, path, attempt, attrs));
    }

    public void record_http_request_succeeded(
            int attempt,
            String method,
            String path,
            int status,
            Optional<String> request_id,
            Map<String, JsonNode> attributes) {
        Map<String, JsonNode> attrs = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        sink.record(new TelemetryEvent.HttpRequestSucceeded(
                session_id, attempt, method, path, status, request_id, new LinkedHashMap<>(attrs)));
        Map<String, JsonNode> trace_attrs = merge_trace_fields(method, path, attempt, attrs);
        trace_attrs.put("status", IntNode.valueOf(status));
        request_id.ifPresent(rid -> trace_attrs.put("request_id", TextNode.valueOf(rid)));
        record("http_request_succeeded", trace_attrs);
    }

    public void record_http_request_failed(
            int attempt,
            String method,
            String path,
            String error,
            boolean retryable,
            Map<String, JsonNode> attributes) {
        Map<String, JsonNode> attrs = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        sink.record(new TelemetryEvent.HttpRequestFailed(
                session_id, attempt, method, path, error, retryable, new LinkedHashMap<>(attrs)));
        Map<String, JsonNode> trace_attrs = merge_trace_fields(method, path, attempt, attrs);
        trace_attrs.put("error", TextNode.valueOf(error));
        trace_attrs.put("retryable", BooleanNode.valueOf(retryable));
        record("http_request_failed", trace_attrs);
    }

    public void record_analytics(AnalyticsEvent event) {
        Map<String, JsonNode> attributes = new LinkedHashMap<>(event.properties());
        attributes.put("namespace", TextNode.valueOf(event.namespace()));
        attributes.put("action", TextNode.valueOf(event.action()));
        sink.record(new TelemetryEvent.Analytics(event));
        record("analytics", attributes);
    }

    private static Map<String, JsonNode> merge_trace_fields(
            String method, String path, int attempt, Map<String, JsonNode> attributes) {
        Map<String, JsonNode> result = new LinkedHashMap<>(attributes);
        result.put("method", TextNode.valueOf(method));
        result.put("path", TextNode.valueOf(path));
        result.put("attempt", IntNode.valueOf(attempt));
        return result;
    }

    /** Used internally for forward compatibility — currently unused but referenced by Rust. */
    @SuppressWarnings("unused")
    private static long current_timestamp_ms() {
        return System.currentTimeMillis();
    }

    @SuppressWarnings("unused")
    private static LongNode l(long v) {
        return LongNode.valueOf(v);
    }
}
