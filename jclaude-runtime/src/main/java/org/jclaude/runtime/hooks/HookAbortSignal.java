package org.jclaude.runtime.hooks;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative abort signal for hook executions. */
public final class HookAbortSignal {
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    public void abort() {
        aborted.set(true);
    }

    public boolean is_aborted() {
        return aborted.get();
    }
}
