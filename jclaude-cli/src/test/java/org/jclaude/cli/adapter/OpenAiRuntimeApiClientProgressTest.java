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
 * Verifies that {@link OpenAiRuntimeApiClient#translate_stream} emits per-chunk signals to a
 * supplied {@link ProgressListener} so the REPL spinner can show live activity.
 */
final class OpenAiRuntimeApiClientProgressTest {

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
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("hello ")),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("world!")),
                new StreamEvent.MessageStop());

        OpenAiRuntimeApiClient.translate_stream(wire, listener);

        assertThat(deltas).containsExactly(6, 6);
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
                new StreamEvent.ContentBlockStart(0, new OutputContentBlock.ToolUse("call_1", "bash", empty_input)),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.InputJsonDelta("{\"command\":\"ls\"}")),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageStop());

        OpenAiRuntimeApiClient.translate_stream(wire, listener);

        assertThat(tools).containsExactly("bash");
    }

    @Test
    void hermes_xml_tool_call_fires_on_tool_starting_once_function_name_is_visible() {
        List<String> tools = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_tool_starting(String tool_name) {
                tools.add(tool_name);
            }
        };

        // Simulate qwen3-coder streaming: text chunks arrive piece-by-piece, and the function name
        // becomes recognizable in the middle of the stream — long before the stop event would fire
        // it as a structured ToolUse. We expect the listener to fire the moment the XML tag closes.
        List<StreamEvent> wire = List.of(
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("<tool_call>\n<function=")),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("read_file>")),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("\n<parameter=path>README.md</parameter>")),
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("\n</function>\n</tool_call>")),
                new StreamEvent.MessageStop());

        OpenAiRuntimeApiClient.translate_stream(wire, listener);

        assertThat(tools).containsExactly("read_file");
    }

    @Test
    void no_listener_overload_still_works_for_non_repl_callers() {
        List<StreamEvent> wire = List.of(
                new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("hello")),
                new StreamEvent.MessageStop());

        // Smoke test: existing single-arg overload must remain callable so tests + one-shot
        // -p prints don't have to construct a no-op listener every time.
        var events = OpenAiRuntimeApiClient.translate_stream(wire);

        assertThat(events).isNotEmpty();
    }
}
