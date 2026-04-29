package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent record of a single named trace event under a session id. */
public record SessionTraceRecord(
        String session_id, long sequence, String name, long timestamp_ms, Map<String, JsonNode> attributes) {

    public SessionTraceRecord {
        attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
    }
}
