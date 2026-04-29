package org.jclaude.runtime.lane;

import java.util.Locale;

/** Lane failure classification. */
public enum LaneFailureClass {
    PROMPT_DELIVERY,
    TRUST_GATE,
    BRANCH_DIVERGENCE,
    COMPILE,
    TEST,
    PLUGIN_STARTUP,
    MCP_STARTUP,
    MCP_HANDSHAKE,
    GATEWAY_ROUTING,
    TOOL_RUNTIME,
    WORKSPACE_MISMATCH,
    INFRA;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
