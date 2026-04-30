package org.jclaude.cli.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputStyle;
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

    /** Maximum body lines to inline under a `⎿  …` continuation in claude-code style. */
    static final int MAX_CLAUDE_CODE_BODY_LINES = 10;

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final PrintStream out;
    private final AnsiPalette palette;
    private final MarkdownStreamRenderer markdown;
    private final ToolResultBox box;
    private final OutputStyle style;

    public TerminalRenderer() {
        this(System.out, AnsiPalette.DEFAULT, OutputStyle.JCLAUDE);
    }

    public TerminalRenderer(PrintStream out, AnsiPalette palette) {
        this(out, palette, OutputStyle.JCLAUDE);
    }

    public TerminalRenderer(PrintStream out, AnsiPalette palette, OutputStyle style) {
        this.out = out;
        this.palette = palette;
        this.markdown = new MarkdownStreamRenderer(palette);
        this.box = new ToolResultBox(palette);
        this.style = style;
    }

    public OutputStyle style() {
        return style;
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
     * Render a complete {@link TurnSummary} interactively. Layout depends on {@link #style}:
     *
     * <ul>
     *   <li>{@link OutputStyle#JCLAUDE} — tool boxes first, then dim {@code [turn]} footer,
     *       then markdown prose at the bottom.
     *   <li>{@link OutputStyle#CLAUDE_CODE} — chronological: prose first, then per-tool
     *       {@code ● <name>(<args>)} headers with indented {@code ⎿  …} body, then a dim
     *       footer line. Mimics the Claude Code CLI feel.
     * </ul>
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

        if (style == OutputStyle.CLAUDE_CODE) {
            render_claude_code(summary, assistant_rendered, compact);
        } else {
            render_jclaude(summary, assistant_rendered, compact);
        }
        out.flush();
    }

    /**
     * jclaude default: tool boxes (audit trail) → dim turn footer → synthesized prose. Putting
     * prose last so the next REPL prompt sits right under the model's final answer.
     */
    private void render_jclaude(TurnSummary summary, String assistant_rendered, boolean compact) {
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
    }

    /**
     * Claude Code-style: prose first, then a sequence of {@code ● tool(arg=value)} headers with an
     * indented {@code ⎿  …} body. Pairs each tool_use with its matching tool_result by id (or by
     * positional fallback), so a turn looks like:
     *
     * <pre>
     * Looking at the file…
     * ● Read(path=README.md)
     *   ⎿  README.md:
     *      # jclaude
     *      A Java 21 port of the Rust …
     *      … (40 more lines)
     * ● Bash(echo hi)
     *   ⎿  hi
     *
     *   iterations=2 · 12.4k input · 60 output
     * </pre>
     */
    private void render_claude_code(TurnSummary summary, String assistant_rendered, boolean compact) {
        if (!assistant_rendered.isEmpty()) {
            out.println(assistant_rendered);
        }
        if (compact) {
            return;
        }
        java.util.Map<String, ContentBlock.ToolResult> results_by_id = new java.util.HashMap<>();
        java.util.List<ContentBlock.ToolResult> results_in_order = new java.util.ArrayList<>();
        for (ConversationMessage message : summary.tool_results()) {
            if (message.role() != MessageRole.TOOL) continue;
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolResult r) {
                    results_in_order.add(r);
                    if (r.tool_use_id() != null) results_by_id.put(r.tool_use_id(), r);
                }
            }
        }
        int positional = 0;
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (!(block instanceof ContentBlock.ToolUse use)) continue;
                String header = "● " + use.name() + "(" + summarize_input(use.input()) + ")";
                ContentBlock.ToolResult r = results_by_id.get(use.id());
                if (r == null && positional < results_in_order.size()) {
                    r = results_in_order.get(positional);
                }
                positional++;

                String header_painted = r != null && r.is_error() ? palette.red(header) : palette.bold(header);
                out.println(header_painted);
                if (r != null) {
                    String pretty = ToolResultPrettyPrinter.format(r.tool_name(), r.output());
                    String trimmed = truncate_for_display(pretty, MAX_CLAUDE_CODE_BODY_LINES);
                    print_indented_body(trimmed, r.is_error());
                }
            }
        }
        // Dim summary footer at the end so the next prompt has breathing room above it.
        out.println();
        out.println(palette.dim("  iterations=" + summary.iterations() + " · "
                + summary.usage().input_tokens() + " input · "
                + summary.usage().output_tokens() + " output"));
    }

    /** One-line input summary like {@code path=README.md, offset=0}. Truncates at 80 chars. */
    private static String summarize_input(String raw_input) {
        if (raw_input == null || raw_input.isBlank()) return "";
        try {
            JsonNode node = MAPPER.readTree(raw_input);
            if (!node.isObject()) return clip(raw_input.replaceAll("\\s+", " "), 80);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext() && count < 4) {
                java.util.Map.Entry<String, JsonNode> e = fields.next();
                if (sb.length() > 0) sb.append(", ");
                String value = e.getValue().isTextual()
                        ? e.getValue().asText()
                        : e.getValue().toString();
                sb.append(e.getKey()).append("=").append(clip(value.replaceAll("\\s+", " "), 40));
                count++;
            }
            if (fields.hasNext()) sb.append(", …");
            return clip(sb.toString(), 80);
        } catch (Exception e) {
            return clip(raw_input.replaceAll("\\s+", " "), 80);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Prefix every body line with {@code "     "}; the first line gets {@code "  ⎿  "} instead. */
    private void print_indented_body(String body, boolean is_error) {
        if (body == null || body.isEmpty()) return;
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String prefix = i == 0 ? "  ⎿  " : "     ";
            String painted_prefix = is_error ? palette.red(prefix) : palette.dim(prefix);
            out.println(painted_prefix + lines[i]);
        }
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
