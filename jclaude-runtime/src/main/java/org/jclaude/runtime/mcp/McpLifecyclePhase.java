package org.jclaude.runtime.mcp;

/**
 * Discrete phases of the MCP server lifecycle, mirroring the Rust enum in
 * {@code mcp_lifecycle_hardened.rs}. The {@link #toString()} value matches the {@code snake_case}
 * serde representation used on the wire.
 */
public enum McpLifecyclePhase {
    CONFIG_LOAD("config_load"),
    SERVER_REGISTRATION("server_registration"),
    SPAWN_CONNECT("spawn_connect"),
    INITIALIZE_HANDSHAKE("initialize_handshake"),
    TOOL_DISCOVERY("tool_discovery"),
    RESOURCE_DISCOVERY("resource_discovery"),
    READY("ready"),
    INVOCATION("invocation"),
    ERROR_SURFACING("error_surfacing"),
    SHUTDOWN("shutdown"),
    CLEANUP("cleanup");

    private final String wire_name;

    McpLifecyclePhase(String wire_name) {
        this.wire_name = wire_name;
    }

    @Override
    public String toString() {
        return wire_name;
    }
}
