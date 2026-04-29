package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** Failure scenarios with known recovery recipes (ROADMAP item 8). */
public enum FailureScenario {
    TRUST_PROMPT_UNRESOLVED("trust_prompt_unresolved"),
    PROMPT_MISDELIVERY("prompt_misdelivery"),
    STALE_BRANCH("stale_branch"),
    COMPILE_RED_CROSS_CRATE("compile_red_cross_crate"),
    MCP_HANDSHAKE_FAILURE("mcp_handshake_failure"),
    PARTIAL_PLUGIN_STARTUP("partial_plugin_startup"),
    PROVIDER_FAILURE("provider_failure");

    private static final List<FailureScenario> ALL = List.of(
            TRUST_PROMPT_UNRESOLVED,
            PROMPT_MISDELIVERY,
            STALE_BRANCH,
            COMPILE_RED_CROSS_CRATE,
            MCP_HANDSHAKE_FAILURE,
            PARTIAL_PLUGIN_STARTUP,
            PROVIDER_FAILURE);

    private final String wire;

    FailureScenario(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static FailureScenario from_wire(String value) {
        return switch (value) {
            case "trust_prompt_unresolved" -> TRUST_PROMPT_UNRESOLVED;
            case "prompt_misdelivery" -> PROMPT_MISDELIVERY;
            case "stale_branch" -> STALE_BRANCH;
            case "compile_red_cross_crate" -> COMPILE_RED_CROSS_CRATE;
            case "mcp_handshake_failure" -> MCP_HANDSHAKE_FAILURE;
            case "partial_plugin_startup" -> PARTIAL_PLUGIN_STARTUP;
            case "provider_failure" -> PROVIDER_FAILURE;
            default -> throw new IllegalArgumentException("unsupported failure scenario: " + value);
        };
    }

    public static List<FailureScenario> all() {
        return ALL;
    }

    /** Bridges a {@link WorkerFailureKind} into the matching {@link FailureScenario}. */
    public static FailureScenario from_worker_failure_kind(WorkerFailureKind kind) {
        return switch (kind) {
            case TRUST_GATE, TOOL_PERMISSION_GATE -> TRUST_PROMPT_UNRESOLVED;
            case PROMPT_DELIVERY -> PROMPT_MISDELIVERY;
            case PROTOCOL -> MCP_HANDSHAKE_FAILURE;
            case PROVIDER, STARTUP_NO_EVIDENCE -> PROVIDER_FAILURE;
        };
    }

    public String display() {
        return wire;
    }

    @Override
    public String toString() {
        return display();
    }
}
