package org.jclaude.cli.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.runtime.conversation.ProgressListener;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AnthropicRuntimeApiClient#translate_stream} reports incremental signals
 * to a supplied {@link ProgressListener}, mirroring the OpenAI adapter behavior.
 */
final class AnthropicRuntimeApiClientProgressTest {

    @Test
    void text_deltas_are_reported_to_listener_with_their_char_count() {
        List<Integer> deltas = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_text_delta_received(int char_count) {
                deltas.add(char_count);
            }
        };

        List<StreamEvent> wire = List.of(
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("hi ")),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("there.")),
                new StreamEvent.MessageStop());

        AnthropicRuntimeApiClient.translate_stream(wire, listener);

        assertThat(deltas).containsExactly(3, 6);
    }

    @Test
    void structured_tool_use_start_fires_on_tool_starting_with_function_name() {
        List<String> tools = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_tool_starting(String tool_name) {
                tools.add(tool_name);
            }
        };

        ObjectNode empty_input = JclaudeMappers.standard().createObjectNode();
        List<StreamEvent> wire = List.of(
                new StreamEvent.ContentBlockStart(0, new OutputContentBlock.ToolUse("call_1", "read_file", empty_input)),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.InputJsonDelta("{\"path\":\"x.txt\"}")),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageStop());

        AnthropicRuntimeApiClient.translate_stream(wire, listener);

        assertThat(tools).containsExactly("read_file");
    }

    @Test
    void no_listener_overload_still_works_for_non_repl_callers() {
        List<StreamEvent> wire = List.of(
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("hi")),
                new StreamEvent.MessageStop());

        var events = AnthropicRuntimeApiClient.translate_stream(wire);

        assertThat(events).isNotEmpty();
    }
}
