package org.jclaude.runtime.conversation;

/** Error returned when a tool invocation fails locally. */
public final class ToolError extends RuntimeException {

    private final String reason;

    public ToolError(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return reason;
    }
}
