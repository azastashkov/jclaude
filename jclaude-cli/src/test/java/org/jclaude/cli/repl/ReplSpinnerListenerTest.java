package org.jclaude.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jclaude.runtime.conversation.ProgressListener;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ProgressListener} that drives the claude-code spinner: text deltas
 * accumulate, tool names are captured, and {@code on_iteration_starting} resets the tool label
 * (but not the cumulative char count) so iteration 2 doesn't keep saying "Calling bash" while the
 * model is actually generating post-tool prose.
 */
class ReplSpinnerListenerTest {

    @Test
    void on_text_delta_received_accumulates_into_char_count() {
        AtomicLong char_count = new AtomicLong(0L);
        AtomicReference<String> latest_tool = new AtomicReference<>(null);

        ProgressListener listener = Repl.build_spinner_listener(char_count, latest_tool);
        listener.on_text_delta_received(120);
        listener.on_text_delta_received(7);

        assertThat(char_count.get()).isEqualTo(127L);
    }

    @Test
    void on_tool_starting_sets_latest_tool() {
        AtomicLong char_count = new AtomicLong(0L);
        AtomicReference<String> latest_tool = new AtomicReference<>(null);

        ProgressListener listener = Repl.build_spinner_listener(char_count, latest_tool);
        listener.on_tool_starting("bash");

        assertThat(latest_tool.get()).isEqualTo("bash");
    }

    @Test
    void on_iteration_starting_clears_latest_tool_so_spinner_label_does_not_stick() {
        AtomicLong char_count = new AtomicLong(0L);
        AtomicReference<String> latest_tool = new AtomicReference<>(null);

        ProgressListener listener = Repl.build_spinner_listener(char_count, latest_tool);
        listener.on_tool_starting("bash");
        // Simulate the conversation runtime starting iteration 2 after the tool result was sent.
        listener.on_iteration_starting();

        // Without this reset, the spinner reads "Calling bash..." while the model is actually
        // streaming post-tool prose (the bug this listener exists to fix).
        assertThat(latest_tool.get()).isNull();
    }

    @Test
    void on_iteration_starting_does_not_reset_cumulative_char_count() {
        AtomicLong char_count = new AtomicLong(0L);
        AtomicReference<String> latest_tool = new AtomicReference<>(null);

        ProgressListener listener = Repl.build_spinner_listener(char_count, latest_tool);
        listener.on_text_delta_received(100);
        listener.on_iteration_starting();
        listener.on_text_delta_received(5);

        // Char count is the per-turn total displayed in the spinner ("↓ N chars"); it must keep
        // accumulating across iterations so the user sees the full turn progress.
        assertThat(char_count.get()).isEqualTo(105L);
    }
}
