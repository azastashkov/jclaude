package org.jclaude.runtime.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressListenerTest {

    @Test
    void no_op_listener_swallows_all_events_without_throwing() {
        ProgressListener listener = ProgressListener.NO_OP;
        listener.on_tool_starting("read_file");
        listener.on_text_delta_received(42);
        listener.on_iteration_starting();
        // Smoke check: NO_OP must be safe to call repeatedly with any payload.
        listener.on_tool_starting("");
        listener.on_text_delta_received(0);
        listener.on_iteration_starting();
    }

    @Test
    void recording_listener_captures_events_in_order() {
        List<String> log = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_tool_starting(String tool_name) {
                log.add("tool:" + tool_name);
            }

            @Override
            public void on_text_delta_received(int char_count) {
                log.add("text:" + char_count);
            }
        };

        listener.on_text_delta_received(120);
        listener.on_tool_starting("bash");
        listener.on_text_delta_received(30);

        assertThat(log).containsExactly("text:120", "tool:bash", "text:30");
    }
}
