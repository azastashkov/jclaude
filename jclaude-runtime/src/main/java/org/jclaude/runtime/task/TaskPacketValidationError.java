package org.jclaude.runtime.task;

import java.util.List;

/** Aggregated validation errors raised when a {@link TaskPacket} fails {@link TaskPackets#validate}. */
public final class TaskPacketValidationError extends RuntimeException {

    private final List<String> errors;

    public TaskPacketValidationError(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskPacketValidationError other)) {
            return false;
        }
        return errors.equals(other.errors);
    }

    @Override
    public int hashCode() {
        return errors.hashCode();
    }
}
