package org.jclaude.runtime.worker;

/** Kinds of worker lifecycle events. */
public enum WorkerEventKind {
    SPAWNING,
    TRUST_REQUIRED,
    TOOL_PERMISSION_REQUIRED,
    TRUST_RESOLVED,
    READY_FOR_PROMPT,
    PROMPT_MISDELIVERY,
    PROMPT_REPLAY_ARMED,
    RUNNING,
    RESTARTED,
    FINISHED,
    FAILED,
    STARTUP_NO_EVIDENCE
}
