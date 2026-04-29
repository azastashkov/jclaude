package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Failure classification surfaced by the worker boot pipeline. The full {@code worker_boot}
 * module ports later — this enum captures only the variants {@link FailureScenario} bridges
 * from.
 */
public enum WorkerFailureKind {
    TRUST_GATE("trust_gate"),
    TOOL_PERMISSION_GATE("tool_permission_gate"),
    PROMPT_DELIVERY("prompt_delivery"),
    PROTOCOL("protocol"),
    PROVIDER("provider"),
    STARTUP_NO_EVIDENCE("startup_no_evidence");

    private final String wire;

    WorkerFailureKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static WorkerFailureKind from_wire(String value) {
        return switch (value) {
            case "trust_gate" -> TRUST_GATE;
            case "tool_permission_gate" -> TOOL_PERMISSION_GATE;
            case "prompt_delivery" -> PROMPT_DELIVERY;
            case "protocol" -> PROTOCOL;
            case "provider" -> PROVIDER;
            case "startup_no_evidence" -> STARTUP_NO_EVIDENCE;
            default -> throw new IllegalArgumentException("unsupported worker failure kind: " + value);
        };
    }
}
