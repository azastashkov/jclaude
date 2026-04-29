package org.jclaude.runtime.task;

/** Wrapper proving that a {@link TaskPacket} has passed runtime validation. */
public final class ValidatedPacket {

    private final TaskPacket packet;

    ValidatedPacket(TaskPacket packet) {
        this.packet = packet;
    }

    public TaskPacket packet() {
        return packet;
    }

    public TaskPacket into_inner() {
        return packet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidatedPacket other)) {
            return false;
        }
        return packet.equals(other.packet);
    }

    @Override
    public int hashCode() {
        return packet.hashCode();
    }
}
