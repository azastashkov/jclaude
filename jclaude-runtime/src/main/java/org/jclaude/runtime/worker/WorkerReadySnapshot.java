package org.jclaude.runtime.worker;

import java.util.Optional;

/** Read-only snapshot of worker readiness. */
public record WorkerReadySnapshot(
        String worker_id,
        WorkerStatus status,
        boolean ready,
        boolean blocked,
        boolean replay_prompt_ready,
        Optional<WorkerFailure> last_error) {

    public WorkerReadySnapshot {
        last_error = last_error == null ? Optional.empty() : last_error;
    }
}
