package org.jclaude.plugins;

/** Hook lifecycle event identifiers used in plugin manifests and the hook runner. */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure");

    private final String label;

    HookEvent(String label) {
        this.label = label;
    }

    public String as_str() {
        return label;
    }
}
