package org.jclaude.runtime.sessioncontrol;

/** Errors raised by the session control store. */
public final class SessionControlError extends RuntimeException {
    public SessionControlError(String message) {
        super(message);
    }

    public SessionControlError(String message, Throwable cause) {
        super(message, cause);
    }
}
