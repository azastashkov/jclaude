package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Policy governing what happens after automatic recovery is exhausted. */
public enum EscalationPolicy {
    ALERT_HUMAN("alert_human"),
    LOG_AND_CONTINUE("log_and_continue"),
    ABORT("abort");

    private final String wire;

    EscalationPolicy(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static EscalationPolicy from_wire(String value) {
        return switch (value) {
            case "alert_human" -> ALERT_HUMAN;
            case "log_and_continue" -> LOG_AND_CONTINUE;
            case "abort" -> ABORT;
            default -> throw new IllegalArgumentException("unsupported escalation policy: " + value);
        };
    }
}
