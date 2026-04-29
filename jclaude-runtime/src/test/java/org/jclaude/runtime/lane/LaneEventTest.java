package org.jclaude.runtime.lane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class LaneEventTest {

    @Test
    void serializes_canonical_event_names() {
        assertThat(LaneEventName.STARTED.wire()).isEqualTo("lane.started");
        assertThat(LaneEventName.PROMPT_MISDELIVERY.wire()).isEqualTo("lane.prompt_misdelivery");
        assertThat(LaneEventName.BRANCH_STALE_AGAINST_MAIN.wire()).isEqualTo("branch.stale_against_main");
        assertThat(LaneEventName.SHIP_PREPARED.wire()).isEqualTo("ship.prepared");
    }

    @Test
    void status_wire_format_is_snake_case() {
        assertThat(LaneEventStatus.RUNNING.wire()).isEqualTo("running");
        assertThat(LaneEventStatus.MERGED.wire()).isEqualTo("merged");
    }

    @Test
    void failure_class_wire_format_is_snake_case() {
        assertThat(LaneFailureClass.PROMPT_DELIVERY.wire()).isEqualTo("prompt_delivery");
        assertThat(LaneFailureClass.MCP_HANDSHAKE.wire()).isEqualTo("mcp_handshake");
    }

    @Test
    void provenance_wire_format_is_snake_case() {
        assertThat(EventProvenance.LIVE_LANE.wire()).isEqualTo("live_lane");
        assertThat(EventProvenance.HEALTHCHECK.wire()).isEqualTo("healthcheck");
    }

    @Test
    void serializes_event_to_json() {
        LaneEvent event =
                LaneEvent.simple(LaneEventName.STARTED, "lane-1", LaneEventStatus.RUNNING, EventProvenance.LIVE_LANE);

        ObjectNode node = event.to_json(new ObjectMapper());

        assertThat(node.get("event").asText()).isEqualTo("lane.started");
        assertThat(node.get("lane_id").asText()).isEqualTo("lane-1");
        assertThat(node.get("status").asText()).isEqualTo("running");
        assertThat(node.get("provenance").asText()).isEqualTo("live_lane");
    }

    @Test
    void session_identity_can_be_constructed_with_helper() {
        SessionIdentity id = SessionIdentity.of("my-task", "/tmp/wt", "code");

        assertThat(id.title()).isEqualTo("my-task");
        assertThat(id.workspace()).isEqualTo("/tmp/wt");
        assertThat(id.purpose()).isEqualTo("code");
        assertThat(id.placeholder_reason()).isEmpty();
    }

    @Test
    void canonical_lane_event_names_serialize_to_expected_wire_values() {
        assertThat(LaneEventName.STARTED.wire()).isEqualTo("lane.started");
        assertThat(LaneEventName.READY.wire()).isEqualTo("lane.ready");
        assertThat(LaneEventName.PROMPT_MISDELIVERY.wire()).isEqualTo("lane.prompt_misdelivery");
        assertThat(LaneEventName.BLOCKED.wire()).isEqualTo("lane.blocked");
        assertThat(LaneEventName.RED.wire()).isEqualTo("lane.red");
        assertThat(LaneEventName.GREEN.wire()).isEqualTo("lane.green");
        assertThat(LaneEventName.COMMIT_CREATED.wire()).isEqualTo("lane.commit.created");
        assertThat(LaneEventName.PR_OPENED.wire()).isEqualTo("lane.pr.opened");
        assertThat(LaneEventName.MERGE_READY.wire()).isEqualTo("lane.merge.ready");
        assertThat(LaneEventName.FINISHED.wire()).isEqualTo("lane.finished");
        assertThat(LaneEventName.FAILED.wire()).isEqualTo("lane.failed");
        assertThat(LaneEventName.RECONCILED.wire()).isEqualTo("lane.reconciled");
        assertThat(LaneEventName.MERGED.wire()).isEqualTo("lane.merged");
        assertThat(LaneEventName.SUPERSEDED.wire()).isEqualTo("lane.superseded");
        assertThat(LaneEventName.CLOSED.wire()).isEqualTo("lane.closed");
        assertThat(LaneEventName.BRANCH_STALE_AGAINST_MAIN.wire()).isEqualTo("branch.stale_against_main");
        assertThat(LaneEventName.BRANCH_WORKSPACE_MISMATCH.wire()).isEqualTo("branch.workspace_mismatch");
        assertThat(LaneEventName.SHIP_PREPARED.wire()).isEqualTo("ship.prepared");
        assertThat(LaneEventName.SHIP_COMMITS_SELECTED.wire()).isEqualTo("ship.commits_selected");
        assertThat(LaneEventName.SHIP_MERGED.wire()).isEqualTo("ship.merged");
        assertThat(LaneEventName.SHIP_PUSHED_MAIN.wire()).isEqualTo("ship.pushed_main");
    }

    @Test
    void failure_classes_cover_canonical_taxonomy_wire_values() {
        assertThat(LaneFailureClass.PROMPT_DELIVERY.wire()).isEqualTo("prompt_delivery");
        assertThat(LaneFailureClass.TRUST_GATE.wire()).isEqualTo("trust_gate");
        assertThat(LaneFailureClass.BRANCH_DIVERGENCE.wire()).isEqualTo("branch_divergence");
        assertThat(LaneFailureClass.COMPILE.wire()).isEqualTo("compile");
        assertThat(LaneFailureClass.TEST.wire()).isEqualTo("test");
        assertThat(LaneFailureClass.PLUGIN_STARTUP.wire()).isEqualTo("plugin_startup");
        assertThat(LaneFailureClass.MCP_STARTUP.wire()).isEqualTo("mcp_startup");
        assertThat(LaneFailureClass.MCP_HANDSHAKE.wire()).isEqualTo("mcp_handshake");
        assertThat(LaneFailureClass.GATEWAY_ROUTING.wire()).isEqualTo("gateway_routing");
        assertThat(LaneFailureClass.TOOL_RUNTIME.wire()).isEqualTo("tool_runtime");
        assertThat(LaneFailureClass.WORKSPACE_MISMATCH.wire()).isEqualTo("workspace_mismatch");
        assertThat(LaneFailureClass.INFRA.wire()).isEqualTo("infra");
    }

    @Test
    void event_provenance_round_trips_through_serialization() {
        for (EventProvenance prov : EventProvenance.values()) {
            EventProvenance parsed = EventProvenance.valueOf(prov.name());
            assertThat(parsed).isEqualTo(prov);
            assertThat(parsed.wire()).isEqualTo(prov.wire());
        }
    }

    @Test
    void session_identity_is_complete_at_creation() {
        SessionIdentity id = new SessionIdentity("title", "/workspace", "purpose", java.util.Optional.empty());
        assertThat(id.title()).isEqualTo("title");
        assertThat(id.workspace()).isEqualTo("/workspace");
        assertThat(id.purpose()).isEqualTo("purpose");
        assertThat(id.placeholder_reason()).isEmpty();
    }
}
