package org.jclaude.cli.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jclaude.runtime.conversation.AssistantEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OpenAiRuntimeApiClient#rewrite_hermes_style_tool_calls} converts qwen3-coder
 * Hermes-template XML tool calls embedded in assistant text into proper {@link
 * AssistantEvent.ToolUse} events.
 */
final class HermesToolCallParserTest {

    @Test
    void plain_text_passes_through_unchanged() {
        List<AssistantEvent> input =
                List.of(new AssistantEvent.TextDelta("Hello world."), AssistantEvent.MessageStop.INSTANCE);
        List<AssistantEvent> output = OpenAiRuntimeApiClient.rewrite_hermes_style_tool_calls(input);
        assertThat(output).hasSize(2);
        assertThat(output.get(0)).isInstanceOf(AssistantEvent.TextDelta.class);
        assertThat(((AssistantEvent.TextDelta) output.get(0)).text()).isEqualTo("Hello world.");
    }

    @Test
    void existing_structured_tool_use_is_preserved_without_double_processing() {
        List<AssistantEvent> input = List.of(
                new AssistantEvent.TextDelta("I'll read that."),
                new AssistantEvent.ToolUse("call_1", "read_file", "{\"path\":\"x.txt\"}"),
                AssistantEvent.MessageStop.INSTANCE);
        List<AssistantEvent> output = OpenAiRuntimeApiClient.rewrite_hermes_style_tool_calls(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void hermes_function_block_is_rewritten_into_tool_use_with_parsed_parameters() {
        String hermes_text =
                "<function=read_file>\n<parameter=path>\nREADME.md\n</parameter>\n</function>\n</tool_call>";
        List<AssistantEvent> input =
                List.of(new AssistantEvent.TextDelta(hermes_text), AssistantEvent.MessageStop.INSTANCE);

        List<AssistantEvent> output = OpenAiRuntimeApiClient.rewrite_hermes_style_tool_calls(input);

        assertThat(output).hasSize(2);
        assertThat(output.get(0)).isInstanceOf(AssistantEvent.ToolUse.class);
        AssistantEvent.ToolUse tool_use = (AssistantEvent.ToolUse) output.get(0);
        assertThat(tool_use.name()).isEqualTo("read_file");
        assertThat(tool_use.input()).contains("\"path\":\"README.md\"");
        assertThat(output.get(1)).isInstanceOf(AssistantEvent.MessageStop.class);
    }

    @Test
    void multiple_function_blocks_yield_multiple_tool_uses_in_order() {
        String hermes_text = "<function=read_file><parameter=path>a.txt</parameter></function>"
                + "Some prose between calls."
                + "<function=write_file><parameter=path>b.txt</parameter><parameter=content>hi</parameter></function>";
        List<AssistantEvent> input =
                List.of(new AssistantEvent.TextDelta(hermes_text), AssistantEvent.MessageStop.INSTANCE);

        List<AssistantEvent> output = OpenAiRuntimeApiClient.rewrite_hermes_style_tool_calls(input);

        // Expected order: cleaned text ("Some prose between calls."), then 2 tool uses, then MessageStop.
        assertThat(output).hasSize(4);
        assertThat(output.get(0)).isInstanceOf(AssistantEvent.TextDelta.class);
        assertThat(((AssistantEvent.TextDelta) output.get(0)).text()).isEqualTo("Some prose between calls.");
        assertThat(((AssistantEvent.ToolUse) output.get(1)).name()).isEqualTo("read_file");
        assertThat(((AssistantEvent.ToolUse) output.get(1)).input()).contains("\"path\":\"a.txt\"");
        assertThat(((AssistantEvent.ToolUse) output.get(2)).name()).isEqualTo("write_file");
        assertThat(((AssistantEvent.ToolUse) output.get(2)).input()).contains("\"path\":\"b.txt\"");
        assertThat(((AssistantEvent.ToolUse) output.get(2)).input()).contains("\"content\":\"hi\"");
        assertThat(output.get(3)).isInstanceOf(AssistantEvent.MessageStop.class);
    }

    @Test
    void tool_call_wrapper_tags_are_stripped_from_cleaned_text() {
        String hermes_text =
                "<tool_call>\n<function=glob_search><parameter=pattern>**/*.java</parameter></function>\n</tool_call>";
        List<AssistantEvent> input =
                List.of(new AssistantEvent.TextDelta(hermes_text), AssistantEvent.MessageStop.INSTANCE);

        List<AssistantEvent> output = OpenAiRuntimeApiClient.rewrite_hermes_style_tool_calls(input);

        // No leftover <tool_call> wrapper text.
        assertThat(output).hasSize(2);
        assertThat(output.get(0)).isInstanceOf(AssistantEvent.ToolUse.class);
        assertThat(((AssistantEvent.ToolUse) output.get(0)).name()).isEqualTo("glob_search");
    }
}
