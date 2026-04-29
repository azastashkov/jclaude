package org.jclaude.runtime.team;

/** Thrown by {@link CronRegistry} mutators when an operation cannot proceed. */
public final class CronRegistryException extends RuntimeException {

    public CronRegistryException(String message) {
        super(message);
    }
}
