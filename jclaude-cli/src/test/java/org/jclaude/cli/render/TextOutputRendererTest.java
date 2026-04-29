package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TextOutputRenderer}. The compact-mode contract is the same one exercised by
 * {@code crates/rusty-claude-cli/tests/compact_output.rs}: only the assistant text appears, no
 * tool-use traces or summary lines.
 */
final class TextOutputRendererTest {

    @Test
    void compact_mode_prints_only_final_assistant_text() {
        // Mirrors `compact_flag_streaming_text_only_emits_final_message_text`
        // from crates/rusty-claude-cli/tests/compact_output.rs:80.
        ConversationMessage assistant = ConversationMessage.assistant(
                List.of(new ContentBlock.ToolUse("toolu_1", "read_file", "{}"), new ContentBlock.Text("final text")));
        TurnSummary summary =
                new TurnSummary(List.of(assistant), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        String written = render_to_string(summary, true);

        assertThat(written.trim()).isEqualTo("final text");
        assertThat(written).doesNotContain("[tool_use]");
        assertThat(written).doesNotContain("[turn]");
    }

    @Test
    void compact_mode_emits_nothing_for_empty_assistant_text() {
        TurnSummary summary = new TurnSummary(List.of(), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        String written = render_to_string(summary, true);

        assertThat(written).isEmpty();
    }

    @Test
    void verbose_mode_emits_tool_use_and_tool_result_lines() {
        ConversationMessage assistant = ConversationMessage.assistant(List.of(
                new ContentBlock.Text("calling tool"),
                new ContentBlock.ToolUse("toolu_5", "bash", "{\"cmd\":\"ls\"}")));
        ConversationMessage tool_result = new ConversationMessage(
                MessageRole.TOOL, List.of(new ContentBlock.ToolResult("toolu_5", "bash", "drwxr", false)), null);
        TurnSummary summary = new TurnSummary(
                List.of(assistant), List.of(tool_result), List.of(), 2, new TokenUsage(20, 5, 0, 0), Optional.empty());

        String written = render_to_string(summary, false);

        assertThat(written).contains("calling tool");
        assertThat(written).contains("[tool_use] bash {\"cmd\":\"ls\"}");
        assertThat(written).contains("[tool_result] bash: drwxr");
        assertThat(written).contains("[turn] iterations=2 input_tokens=20 output_tokens=5");
    }

    @Test
    void verbose_mode_marks_tool_errors_distinctly() {
        ConversationMessage tool_result = new ConversationMessage(
                MessageRole.TOOL, List.of(new ContentBlock.ToolResult("toolu_6", "bash", "boom", true)), null);
        TurnSummary summary =
                new TurnSummary(List.of(), List.of(tool_result), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        String written = render_to_string(summary, false);

        assertThat(written).contains("[tool_error] bash: boom");
        assertThat(written).doesNotContain("[tool_result] bash:");
    }

    private static String render_to_string(TurnSummary summary, boolean compact) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(buffer)) {
            new TextOutputRenderer(stream).render(summary, compact);
        }
        return buffer.toString();
    }
}
