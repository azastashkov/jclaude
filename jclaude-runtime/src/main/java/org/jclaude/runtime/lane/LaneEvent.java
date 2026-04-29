package org.jclaude.runtime.lane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/** Canonical lane event record. */
public record LaneEvent(
        LaneEventName event,
        String lane_id,
        long timestamp,
        LaneEventStatus status,
        Optional<LaneFailureClass> failure_class,
        Optional<String> failure_message,
        EventProvenance provenance,
        Optional<SessionIdentity> session_identity,
        Optional<JsonNode> payload) {

    public LaneEvent {
        failure_class = failure_class == null ? Optional.empty() : failure_class;
        failure_message = failure_message == null ? Optional.empty() : failure_message;
        session_identity = session_identity == null ? Optional.empty() : session_identity;
        payload = payload == null ? Optional.empty() : payload;
    }

    public static LaneEvent simple(LaneEventName name, String lane_id, LaneEventStatus status, EventProvenance prov) {
        return new LaneEvent(
                name,
                lane_id,
                System.currentTimeMillis() / 1000L,
                status,
                Optional.empty(),
                Optional.empty(),
                prov,
                Optional.empty(),
                Optional.empty());
    }

    public ObjectNode to_json(ObjectMapper json) {
        ObjectNode node = json.createObjectNode();
        node.put("event", event.wire());
        node.put("lane_id", lane_id);
        node.put("timestamp", timestamp);
        node.put("status", status.wire());
        failure_class.ifPresent(f -> node.put("failure_class", f.wire()));
        failure_message.ifPresent(m -> node.put("failure_message", m));
        node.put("provenance", provenance.wire());
        session_identity.ifPresent(s -> {
            ObjectNode obj = node.putObject("session_identity");
            obj.put("title", s.title());
            obj.put("workspace", s.workspace());
            obj.put("purpose", s.purpose());
            s.placeholder_reason().ifPresent(p -> obj.put("placeholder_reason", p));
        });
        payload.ifPresent(p -> node.set("payload", p));
        return node;
    }
}
