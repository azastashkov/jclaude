package org.jclaude.runtime.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle status for a {@link Task}. */
public enum TaskStatus {
    CREATED("created"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    STOPPED("stopped");

    private final String wire;

    TaskStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static TaskStatus from_wire(String value) {
        return switch (value) {
            case "created" -> CREATED;
            case "running" -> RUNNING;
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "stopped" -> STOPPED;
            default -> throw new IllegalArgumentException("unsupported task status: " + value);
        };
    }

    /** Display string matching Rust's {@code Display} implementation (lowercase variant). */
    public String display() {
        return wire;
    }

    @Override
    public String toString() {
        return display();
    }
}
