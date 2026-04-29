package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle status for a registered LSP server. */
public enum LspServerStatus {
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    STARTING("starting"),
    ERROR("error");

    private final String wire;

    LspServerStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static LspServerStatus from_wire(String value) {
        return switch (value) {
            case "connected" -> CONNECTED;
            case "disconnected" -> DISCONNECTED;
            case "starting" -> STARTING;
            case "error" -> ERROR;
            default -> throw new IllegalArgumentException("unsupported lsp server status: " + value);
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
