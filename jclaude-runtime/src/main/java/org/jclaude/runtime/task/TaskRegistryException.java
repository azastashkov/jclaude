package org.jclaude.runtime.task;

/** Thrown by {@link TaskRegistry} mutators when an operation cannot proceed. */
public final class TaskRegistryException extends RuntimeException {

    public TaskRegistryException(String message) {
        super(message);
    }
}
