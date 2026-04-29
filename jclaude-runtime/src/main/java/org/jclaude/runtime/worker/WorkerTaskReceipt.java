package org.jclaude.runtime.worker;

import java.util.List;

/** Receipt of an expected task associated with a prompt. */
public record WorkerTaskReceipt(
        String repo,
        String task_kind,
        String source_surface,
        List<String> expected_artifacts,
        String objective_preview) {

    public WorkerTaskReceipt {
        expected_artifacts = List.copyOf(expected_artifacts);
    }
}
