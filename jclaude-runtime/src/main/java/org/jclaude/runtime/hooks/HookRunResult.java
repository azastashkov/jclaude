package org.jclaude.runtime.hooks;

import java.util.List;
import java.util.Optional;

/** Result of running a hook. */
public record HookRunResult(
        boolean denied,
        boolean failed,
        boolean cancelled,
        List<String> messages,
        Optional<String> permission_override,
        Optional<String> permission_reason,
        Optional<String> updated_input) {

    public HookRunResult {
        messages = List.copyOf(messages);
        permission_override = permission_override == null ? Optional.empty() : permission_override;
        permission_reason = permission_reason == null ? Optional.empty() : permission_reason;
        updated_input = updated_input == null ? Optional.empty() : updated_input;
    }

    public static HookRunResult allow(List<String> messages) {
        return new HookRunResult(false, false, false, messages, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static HookRunResult deny(List<String> messages) {
        return new HookRunResult(true, false, false, messages, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static HookRunResult fail(List<String> messages) {
        return new HookRunResult(false, true, false, messages, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static HookRunResult cancelled(List<String> messages) {
        return new HookRunResult(false, false, true, messages, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
