package org.jclaude.runtime.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskPacketsTest {

    private static TaskPacket sample_packet() {
        return new TaskPacket(
                "Implement typed task packet format",
                TaskScope.MODULE,
                "runtime/task system",
                "claw-code-parity",
                "/tmp/wt-1",
                "origin/main only",
                List.of("cargo build --workspace", "cargo test --workspace"),
                "single verified commit",
                "print build result, test result, commit sha",
                "stop only on destructive ambiguity");
    }

    @Test
    void valid_packet_passes_validation() {
        TaskPacket packet = sample_packet();
        ValidatedPacket validated = TaskPackets.validate(packet);
        assertThat(validated.packet()).isEqualTo(packet);
        assertThat(validated.into_inner()).isEqualTo(packet);
    }

    @Test
    void invalid_packet_accumulates_errors() {
        TaskPacket packet =
                new TaskPacket(" ", TaskScope.WORKSPACE, null, "", null, "\t", List.of("ok", " "), "", "", "");

        TaskPacketValidationError error = catchValidation(() -> TaskPackets.validate(packet));

        assertThat(error.errors()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(error.errors()).contains("objective must not be empty");
        assertThat(error.errors()).contains("repo must not be empty");
        assertThat(error.errors()).contains("acceptance_tests contains an empty value at index 1");
    }

    private static TaskPacketValidationError catchValidation(Runnable action) {
        try {
            action.run();
        } catch (TaskPacketValidationError caught) {
            return caught;
        }
        throw new AssertionError("expected TaskPacketValidationError");
    }

    @Test
    void serialization_roundtrip_preserves_packet() throws Exception {
        TaskPacket packet = sample_packet();
        ObjectMapper mapper = new ObjectMapper();
        String serialized = mapper.writeValueAsString(packet);
        TaskPacket deserialized = mapper.readValue(serialized, TaskPacket.class);
        assertThat(deserialized).isEqualTo(packet);
    }
}
