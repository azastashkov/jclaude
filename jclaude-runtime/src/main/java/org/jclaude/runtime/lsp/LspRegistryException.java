package org.jclaude.runtime.lsp;

/** Thrown by {@link LspRegistry} when an operation cannot proceed. */
public final class LspRegistryException extends RuntimeException {

    public LspRegistryException(String message) {
        super(message);
    }
}
