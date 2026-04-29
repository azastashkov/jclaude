package org.jclaude.runtime.worker;

/** Kinds of worker failure. */
public enum WorkerFailureKind {
    TRUST_GATE,
    TOOL_PERMISSION_GATE,
    PROMPT_DELIVERY,
    PROTOCOL,
    PROVIDER,
    STARTUP_NO_EVIDENCE
}
