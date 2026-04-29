package org.jclaude.runtime.files;

import java.io.IOException;

/**
 * Signals a failure originating in a file-ops tool. Carries a kind tag so callers can map
 * Rust-style {@code ErrorKind} expectations onto the Java side.
 */
public final class FileOpsException extends IOException {

    private final Kind kind;

    public FileOpsException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FileOpsException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Mirrors the subset of {@code std::io::ErrorKind} variants used in the Rust source.
     */
    public enum Kind {
        INVALID_DATA,
        INVALID_INPUT,
        NOT_FOUND,
        PERMISSION_DENIED,
        OTHER
    }
}
