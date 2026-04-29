package org.jclaude.runtime.lane;

import java.util.Locale;

/** Lane status enumeration. */
public enum LaneEventStatus {
    RUNNING,
    READY,
    BLOCKED,
    RED,
    GREEN,
    COMPLETED,
    FAILED,
    RECONCILED,
    MERGED,
    SUPERSEDED,
    CLOSED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
