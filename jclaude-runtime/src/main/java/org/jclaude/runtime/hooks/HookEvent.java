package org.jclaude.runtime.hooks;

/** Hook lifecycle events. */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure");

    private final String wire;

    HookEvent(String wire) {
        this.wire = wire;
    }

    public String as_str() {
        return wire;
    }
}
