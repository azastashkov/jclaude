package org.jclaude.runtime.worker;

/** Detected target of a misdelivered prompt. */
public enum WorkerPromptTarget {
    SHELL,
    WRONG_TARGET,
    WRONG_TASK,
    UNKNOWN
}
