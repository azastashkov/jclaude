package org.jclaude.plugins;

import java.util.List;

/** Outcome of a {@link HookRunner} invocation: allow / deny / fail with collected messages. */
public record HookRunResult(boolean denied, boolean failed, List<String> messages) {

    public HookRunResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static HookRunResult allow(List<String> messages) {
        return new HookRunResult(false, false, messages == null ? List.of() : messages);
    }

    public boolean is_denied() {
        return denied;
    }

    public boolean is_failed() {
        return failed;
    }
}
