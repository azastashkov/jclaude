package org.jclaude.cli.render;

import java.io.PrintStream;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;

/**
 * Orchestrates the streaming TUI: pretty-prints assistant text via {@link MarkdownStreamRenderer},
 * tool results via {@link ToolResultBox}, and (when the surrounding REPL drives it) animates a
 * {@link Spinner} during long-running tool calls.
 *
 * <p>This renderer augments — but does not replace — the existing {@link TextOutputRenderer}. The
 * one-shot CLI entry point falls back to {@link TextOutputRenderer} when colors are disabled or
 * the output stream is not a TTY; the REPL always uses {@link TerminalRenderer}.
 */
public final class TerminalRenderer {

    /**
     * Maximum number of lines a single tool result is allowed to occupy in the REPL display. The
     * model still sees the full content (the runtime forwards the untruncated JSON envelope on the
     * next turn); this cap exists purely so a `read_file` of a 5000-line source doesn't bury the
     * prompt off-screen.
     */
    static final int MAX_DISPLAY_LINES = 40;

    private final PrintStream out;
    private final AnsiPalette palette;
    private final MarkdownStreamRenderer markdown;
    private final ToolResultBox box;

    public TerminalRenderer() {
        this(System.out, AnsiPalette.DEFAULT);
    }

    public TerminalRenderer(PrintStream out, AnsiPalette palette) {
        this.out = out;
        this.palette = palette;
        this.markdown = new MarkdownStreamRenderer(palette);
        this.box = new ToolResultBox(palette);
    }

    public AnsiPalette palette() {
        return palette;
    }

    public MarkdownStreamRenderer markdown() {
        return markdown;
    }

    public ToolResultBox box() {
        return box;
    }

    /**
     * Render a complete {@link TurnSummary} interactively: assistant text as ANSI markdown, tool
     * results as colored boxes, and the iteration footer as a dim line. Mirrors the layout of the
     * Rust REPL renderer.
     */
    public void render(TurnSummary summary, boolean compact) {
        StringBuilder assistant = new StringBuilder();
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.Text text) {
                    if (assistant.length() > 0) {
                        assistant.append('\n');
                    }
                    assistant.append(text.text());
                }
            }
        }
        String assistant_rendered = assistant.length() > 0 ? markdown.render(assistant.toString()) : "";

        // Layout order: tool boxes first (the audit trail), then the dim turn footer, then the
        // synthesized prose. Putting the prose last means the next REPL prompt sits right under
        // the model's final answer instead of below a wall of tool boxes — the user can read the
        // answer without scrolling past every read_file/glob_search result.
        if (!compact) {
            for (ConversationMessage message : summary.tool_results()) {
                if (message.role() != MessageRole.TOOL) {
                    continue;
                }
                for (ContentBlock block : message.blocks()) {
                    if (block instanceof ContentBlock.ToolResult result) {
                        String pretty = ToolResultPrettyPrinter.format(result.tool_name(), result.output());
                        String trimmed = truncate_for_display(pretty, MAX_DISPLAY_LINES);
                        out.println(box.render(result.tool_name(), trimmed, result.is_error()));
                    }
                }
            }
            out.println(palette.dim("[turn] iterations=" + summary.iterations()
                    + " input_tokens=" + summary.usage().input_tokens()
                    + " output_tokens=" + summary.usage().output_tokens()));
        }

        if (!assistant_rendered.isEmpty()) {
            out.println(assistant_rendered);
        }
        out.flush();
    }

    /**
     * Cap a multi-line text to {@code max_lines} for in-REPL display, appending a footer like
     * {@code … (N more lines)} when truncated. Operates on logical newlines; the caller
     * (typically a soft-wrapping renderer) is free to re-flow each kept line.
     */
    static String truncate_for_display(String text, int max_lines) {
        if (text == null || max_lines <= 0) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length <= max_lines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max_lines; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        sb.append('\n').append("… (").append(lines.length - max_lines).append(" more lines)");
        return sb.toString();
    }
}
