package org.jclaude.runtime.team;

/** Thrown by {@link TeamRegistry} mutators when an operation cannot proceed. */
public final class TeamRegistryException extends RuntimeException {

    public TeamRegistryException(String message) {
        super(message);
    }
}
