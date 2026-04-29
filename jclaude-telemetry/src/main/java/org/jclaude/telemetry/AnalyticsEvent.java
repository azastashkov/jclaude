package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generic analytics event emitted alongside HTTP/session telemetry. */
public final class AnalyticsEvent {

    private final String namespace;
    private final String action;
    private final Map<String, JsonNode> properties;

    public AnalyticsEvent(String namespace, String action, Map<String, JsonNode> properties) {
        this.namespace = namespace;
        this.action = action;
        this.properties = new LinkedHashMap<>(properties == null ? Map.of() : properties);
    }

    public static AnalyticsEvent of(String namespace, String action) {
        return new AnalyticsEvent(namespace, action, new LinkedHashMap<>());
    }

    public String namespace() {
        return namespace;
    }

    public String action() {
        return action;
    }

    public Map<String, JsonNode> properties() {
        return new LinkedHashMap<>(properties);
    }

    public AnalyticsEvent with_property(String key, JsonNode value) {
        Map<String, JsonNode> next = new LinkedHashMap<>(properties);
        next.put(key, value);
        return new AnalyticsEvent(namespace, action, next);
    }
}
