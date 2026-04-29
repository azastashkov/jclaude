package org.jclaude.runtime.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Task scope resolution for defining the granularity of work. */
public enum TaskScope {
    /** Work across the entire workspace. */
    WORKSPACE("workspace"),
    /** Work within a specific module/crate. */
    MODULE("module"),
    /** Work on a single file. */
    SINGLE_FILE("single_file"),
    /** Custom scope defined by the user. */
    CUSTOM("custom");

    private final String wire;

    TaskScope(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static TaskScope from_wire(String value) {
        return switch (value) {
            case "workspace" -> WORKSPACE;
            case "module" -> MODULE;
            case "single_file" -> SINGLE_FILE;
            case "custom" -> CUSTOM;
            default -> throw new IllegalArgumentException("unsupported task scope: " + value);
        };
    }

    /** Display string matching the Rust {@code Display} implementation. */
    public String display() {
        return switch (this) {
            case WORKSPACE -> "workspace";
            case MODULE -> "module";
            case SINGLE_FILE -> "single-file";
            case CUSTOM -> "custom";
        };
    }

    @Override
    public String toString() {
        return display();
    }
}
