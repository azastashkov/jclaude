package org.jclaude.runtime.worker;

/** Classification of startup failures. */
public enum StartupFailureClassification {
    TRUST_REQUIRED,
    TOOL_PERMISSION_REQUIRED,
    PROMPT_MISDELIVERY,
    PROMPT_ACCEPTANCE_TIMEOUT,
    TRANSPORT_DEAD,
    WORKER_CRASHED,
    UNKNOWN
}
