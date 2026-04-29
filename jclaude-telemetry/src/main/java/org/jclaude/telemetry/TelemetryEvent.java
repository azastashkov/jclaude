package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Sealed family of telemetry events emitted by the harness, mirroring the Rust enum. */
public sealed interface TelemetryEvent {

    String type_label();

    record HttpRequestStarted(
            String session_id, int attempt, String method, String path, Map<String, JsonNode> attributes)
            implements TelemetryEvent {
        public HttpRequestStarted {
            attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        }

        @Override
        public String type_label() {
            return "http_request_started";
        }
    }

    record HttpRequestSucceeded(
            String session_id,
            int attempt,
            String method,
            String path,
            int status,
            Optional<String> request_id,
            Map<String, JsonNode> attributes)
            implements TelemetryEvent {
        public HttpRequestSucceeded {
            request_id = request_id == null ? Optional.empty() : request_id;
            attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        }

        @Override
        public String type_label() {
            return "http_request_succeeded";
        }
    }

    record HttpRequestFailed(
            String session_id,
            int attempt,
            String method,
            String path,
            String error,
            boolean retryable,
            Map<String, JsonNode> attributes)
            implements TelemetryEvent {
        public HttpRequestFailed {
            attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        }

        @Override
        public String type_label() {
            return "http_request_failed";
        }
    }

    record Analytics(AnalyticsEvent event) implements TelemetryEvent {
        @Override
        public String type_label() {
            return "analytics";
        }
    }

    record SessionTrace(SessionTraceRecord record) implements TelemetryEvent {
        @Override
        public String type_label() {
            return "session_trace";
        }
    }
}
