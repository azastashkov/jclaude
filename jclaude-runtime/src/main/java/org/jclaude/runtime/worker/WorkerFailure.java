package org.jclaude.runtime.worker;

/** Worker failure record. */
public record WorkerFailure(WorkerFailureKind kind, String message, long created_at) {}
