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
        if (assistant.length() > 0) {
            String rendered = markdown.render(assistant.toString());
            if (!rendered.isEmpty()) {
                out.println(rendered);
            }
        }

        if (compact) {
            out.flush();
            return;
        }

        // Tool uses → tool results: emit each result inside a box.
        for (ConversationMessage message : summary.tool_results()) {
            if (message.role() != MessageRole.TOOL) {
                continue;
            }
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolResult result) {
                    out.println(box.render(result.tool_name(), result.output(), result.is_error()));
                }
            }
        }

        out.println(palette.dim("[turn] iterations=" + summary.iterations()
                + " input_tokens=" + summary.usage().input_tokens()
                + " output_tokens=" + summary.usage().output_tokens()));
        out.flush();
    }
}
