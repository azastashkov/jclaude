package org.jclaude.runtime.conversation;

/** Error returned when a conversation turn cannot be completed. */
public final class RuntimeError extends RuntimeException {

    private final String reason;

    public RuntimeError(String reason) {
        super(reason);
        this.reason = reason;
    }

    public RuntimeError(String reason, Throwable cause) {
        super(reason, cause);
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
