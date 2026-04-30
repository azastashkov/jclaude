package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jclaude.cli.OutputStyle;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;

/** Smoke tests for the two {@link OutputStyle} variants of {@link TerminalRenderer}. */
final class TerminalRendererStyleTest {

    private static TurnSummary build_summary() {
        ConversationMessage assistant = new ConversationMessage(
                MessageRole.ASSISTANT,
                List.of(
                        new ContentBlock.Text("I'll read the file then echo a value."),
                        new ContentBlock.ToolUse("call_1", "read_file", "{\"path\":\"README.md\"}"),
                        new ContentBlock.ToolUse("call_2", "bash", "{\"command\":\"echo hi\"}")),
                null);
        ConversationMessage results = new ConversationMessage(
                MessageRole.TOOL,
                List.of(
                        new ContentBlock.ToolResult(
                                "call_1",
                                "read_file",
                                "{\"kind\":\"text\",\"file\":{\"file_path\":\"README.md\","
                                        + "\"content\":\"# jclaude\\nLine two\"}}",
                                false),
                        new ContentBlock.ToolResult(
                                "call_2",
                                "bash",
                                "{\"stdout\":\"hi\\n\",\"stderr\":\"\",\"exit_code\":0,\"timed_out\":false}",
                                false)),
                null);
        return new TurnSummary(
                List.of(assistant), List.of(results), List.of(), 2, new TokenUsage(120, 30, 0, 0), Optional.empty());
    }

    @Test
    void jclaude_style_emits_rounded_box_then_footer_then_prose() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        TerminalRenderer renderer = new TerminalRenderer(out, AnsiPalette.with_color_disabled(), OutputStyle.JCLAUDE);

        renderer.render(build_summary(), false);

        String text = buf.toString(StandardCharsets.UTF_8);
        // Boxed tool result first.
        assertThat(text).contains("╭─ read_file");
        assertThat(text).contains("╭─ bash");
        // Footer.
        assertThat(text).contains("[turn] iterations=2");
        // Prose at the end (after the boxes).
        int last_box_end = text.lastIndexOf("╯");
        int prose_idx = text.indexOf("I'll read the file");
        assertThat(prose_idx).isGreaterThan(last_box_end);
    }

    @Test
    void claude_code_style_emits_bullet_headers_then_prose_then_footer() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        TerminalRenderer renderer =
                new TerminalRenderer(out, AnsiPalette.with_color_disabled(), OutputStyle.CLAUDE_CODE);

        renderer.render(build_summary(), false);

        String text = buf.toString(StandardCharsets.UTF_8);
        // Bullet headers with one-line input summary.
        assertThat(text).contains("● read_file(path=README.md)");
        assertThat(text).contains("● bash(command=echo hi)");
        // Indented body under ⎿ — terse one-liner per Claude Code convention, NOT the file body.
        assertThat(text).contains("⎿");
        assertThat(text).contains("Read"); // "Read N lines (M B)"
        assertThat(text).doesNotContain("# jclaude"); // file body must NOT appear in cc-mode
        // Tool calls come BEFORE prose (chronological).
        int first_bullet = text.indexOf("●");
        int prose_idx = text.indexOf("I'll read the file");
        assertThat(first_bullet).isGreaterThanOrEqualTo(0);
        assertThat(prose_idx).isGreaterThan(first_bullet);
        // Footer comes after both, with bullet-style summary.
        int footer_idx = text.indexOf("iterations=2 · 120 input · 30 output");
        assertThat(footer_idx).isGreaterThan(prose_idx);
        // No rounded box characters in claude-code mode.
        assertThat(text).doesNotContain("╭─");
        assertThat(text).doesNotContain("╰─");
    }

    @Test
    void claude_code_style_compact_suppresses_tool_blocks() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        TerminalRenderer renderer =
                new TerminalRenderer(out, AnsiPalette.with_color_disabled(), OutputStyle.CLAUDE_CODE);

        renderer.render(build_summary(), true);

        String text = buf.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("I'll read the file");
        assertThat(text).doesNotContain("●");
        assertThat(text).doesNotContain("⎿");
    }

    @Test
    void error_tool_result_renders_in_red_in_claude_code_style() {
        ConversationMessage assistant = new ConversationMessage(
                MessageRole.ASSISTANT,
                List.of(new ContentBlock.ToolUse("call_x", "read_file", "{\"path\":\"missing.txt\"}")),
                null);
        ConversationMessage results = new ConversationMessage(
                MessageRole.TOOL,
                List.of(new ContentBlock.ToolResult("call_x", "read_file", "no such file: missing.txt", true)),
                null);
        TurnSummary summary = new TurnSummary(
                List.of(assistant), List.of(results), List.of(), 1, new TokenUsage(10, 0, 0, 0), Optional.empty());

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        new TerminalRenderer(out, AnsiPalette.with_color_enabled(), OutputStyle.CLAUDE_CODE).render(summary, false);

        String text = buf.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("● read_file(path=missing.txt)");
        // Header line is colored red on errors.
        assertThat(text).contains(AnsiPalette.RED + "● read_file");
    }
}
