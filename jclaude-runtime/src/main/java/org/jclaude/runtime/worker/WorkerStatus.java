package org.jclaude.runtime.worker;

import java.util.Locale;

/** Worker lifecycle states. */
public enum WorkerStatus {
    SPAWNING,
    TRUST_REQUIRED,
    TOOL_PERMISSION_REQUIRED,
    READY_FOR_PROMPT,
    RUNNING,
    FINISHED,
    FAILED;

    public String display() {
        return name().toLowerCase(Locale.ROOT);
    }
}
