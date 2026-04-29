package org.jclaude.runtime.session;

/** Errors raised while loading, parsing, or saving sessions. */
public sealed class SessionError extends RuntimeException {

    SessionError(String message) {
        super(message);
    }

    SessionError(String message, Throwable cause) {
        super(message, cause);
    }

    public static SessionError io(java.io.IOException cause) {
        return new Io(cause);
    }

    public static SessionError json(Throwable cause) {
        return new Json(cause);
    }

    public static SessionError format(String message) {
        return new Format(message);
    }

    /** I/O failure while reading or writing the session file. */
    public static final class Io extends SessionError {
        public Io(java.io.IOException cause) {
            super(cause.getMessage() == null ? "io error" : cause.getMessage(), cause);
        }
    }

    /** JSON parsing or serialization failure. */
    public static final class Json extends SessionError {
        public Json(Throwable cause) {
            super(cause.getMessage() == null ? "json error" : cause.getMessage(), cause);
        }
    }

    /** Schema or shape mismatch in the session file. */
    public static final class Format extends SessionError {
        public Format(String message) {
            super(message);
        }
    }
}
