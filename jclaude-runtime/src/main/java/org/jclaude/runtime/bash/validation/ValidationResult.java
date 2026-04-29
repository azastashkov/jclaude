package org.jclaude.runtime.bash.validation;

/** Result of validating a bash command before execution. */
public sealed interface ValidationResult {
    /** Command is safe to execute. */
    record Allow() implements ValidationResult {}

    /** Command should be blocked with the given reason. */
    record Block(String reason) implements ValidationResult {}

    /** Command requires user confirmation with the given warning. */
    record Warn(String message) implements ValidationResult {}

    static ValidationResult allow() {
        return new Allow();
    }

    static ValidationResult block(String reason) {
        return new Block(reason);
    }

    static ValidationResult warn(String message) {
        return new Warn(message);
    }
}
