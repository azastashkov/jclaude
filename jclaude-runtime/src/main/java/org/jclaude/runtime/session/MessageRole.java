package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Speaker role associated with a persisted conversation message. */
public enum MessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wire;

    MessageRole(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static MessageRole from_wire(String value) {
        return switch (value) {
            case "system" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "tool" -> TOOL;
            default -> throw new IllegalArgumentException("unsupported message role: " + value);
        };
    }
}
