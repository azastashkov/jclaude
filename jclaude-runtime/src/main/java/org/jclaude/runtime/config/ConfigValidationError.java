package org.jclaude.runtime.config;

/** Thrown when a settings file fails validation. */
public final class ConfigValidationError extends RuntimeException {
    public ConfigValidationError(String message) {
        super(message);
    }
}
