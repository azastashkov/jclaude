package org.jclaude.runtime.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle status for a {@link Team}. */
public enum TeamStatus {
    CREATED("created"),
    RUNNING("running"),
    COMPLETED("completed"),
    DELETED("deleted");

    private final String wire;

    TeamStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static TeamStatus from_wire(String value) {
        return switch (value) {
            case "created" -> CREATED;
            case "running" -> RUNNING;
            case "completed" -> COMPLETED;
            case "deleted" -> DELETED;
            default -> throw new IllegalArgumentException("unsupported team status: " + value);
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
