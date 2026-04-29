package org.jclaude.runtime.worker;

import java.util.Optional;

/** Worker lifecycle event record. */
public record WorkerEvent(
        long seq,
        WorkerEventKind kind,
        WorkerStatus status,
        Optional<String> detail,
        Optional<WorkerEventPayload> payload,
        long timestamp) {

    public WorkerEvent {
        detail = detail == null ? Optional.empty() : detail;
        payload = payload == null ? Optional.empty() : payload;
    }
}
