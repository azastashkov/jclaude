package org.jclaude.cli.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a tool result inside box-drawing borders. Mirrors the Rust pretty-print output:
 *
 * <pre>
 * ╭─ read_file ─╮
 * │ ✓ alpha     │
 * │   beta      │
 * ╰─────────────╯
 * </pre>
 *
 * <p>Borders are colored green for successful results and red for tool errors. The header carries
 * the tool name; an optional status icon ({@code ✓} or {@code ✗}) precedes the first body line.
 *
 * <p>This class is stateless — call {@link #render(String, String, boolean)} or
 * {@link #render(String, String, boolean, AnsiPalette)} per result.
 */
public final class ToolResultBox {

    private static final String CORNER_TOP_LEFT = "╭";
    private static final String CORNER_TOP_RIGHT = "╮";
    private static final String CORNER_BOTTOM_LEFT = "╰";
    private static final String CORNER_BOTTOM_RIGHT = "╯";
    private static final String HORIZONTAL = "─";
    private static final String VERTICAL = "│";

    private final AnsiPalette palette;

    public ToolResultBox() {
        this(AnsiPalette.DEFAULT);
    }

    public ToolResultBox(AnsiPalette palette) {
        this.palette = palette;
    }

    /** Default visible width cap for box body lines (excluding borders + icon prefix). */
    private static final int DEFAULT_MAX_BODY_WIDTH = 100;

    /**
     * Format a tool-result block. Multi-line output is split on {@code \n} and each line wrapped in
     * a side border; the box auto-sizes to the longest visible content (header label or any body
     * line, whichever is wider). Long lines are soft-wrapped at {@value #DEFAULT_MAX_BODY_WIDTH}
     * visible characters so a single huge line doesn't blow out the terminal.
     */
    public String render(String tool_name, String output, boolean is_error) {
        return render(tool_name, output, is_error, palette);
    }

    /** Same as {@link #render(String, String, boolean)} but using the supplied palette. */
    public String render(String tool_name, String output, boolean is_error, AnsiPalette palette_override) {
        AnsiPalette p = palette_override == null ? AnsiPalette.with_color_disabled() : palette_override;
        String safe_name = tool_name == null ? "" : tool_name;
        String safe_output = output == null ? "" : output;

        List<String> body_lines = new ArrayList<>();
        if (safe_output.isEmpty()) {
            body_lines.add("");
        } else {
            for (String raw_line : safe_output.split("\n", -1)) {
                for (String wrapped : soft_wrap(raw_line, DEFAULT_MAX_BODY_WIDTH)) {
                    body_lines.add(wrapped);
                }
            }
            // split with limit -1 preserves trailing empty strings; trim a single trailing empty
            // line if the content ended with a newline so we don't render a blank line at the end.
            if (body_lines.size() > 1 && body_lines.get(body_lines.size() - 1).isEmpty()) {
                body_lines.remove(body_lines.size() - 1);
            }
        }

        String icon = is_error ? "✗" : "✓";
        String first_body_with_icon = body_lines.isEmpty() ? icon + " " : icon + " " + body_lines.get(0);

        int header_visible = visible_length(" " + safe_name + " ") + 2; // "─ name ─" with two ─
        int body_visible = visible_length(first_body_with_icon);
        for (int i = 1; i < body_lines.size(); i++) {
            int line_visible = visible_length("  " + body_lines.get(i));
            if (line_visible > body_visible) {
                body_visible = line_visible;
            }
        }
        int interior = Math.max(header_visible, body_visible);

        StringBuilder builder = new StringBuilder();
        // Header: ╭─ name ─...─╮
        // Total visible width is `interior + 3` to match body lines (│ + space + content padded to
        // interior + │) and the footer (╰ + interior+1 dashes + ╯). The header itself is
        // ╭ + ─ + " name " + N×─ + ╮, so N = interior - visible(" name ").
        String header_label = safe_name.isEmpty() ? "" : (" " + safe_name + " ");
        int header_dashes_after = Math.max(1, interior - visible_length(header_label));
        String header_line = CORNER_TOP_LEFT
                + HORIZONTAL
                + header_label
                + repeat(HORIZONTAL, header_dashes_after)
                + CORNER_TOP_RIGHT;
        builder.append(color_border(header_line, is_error, p));
        builder.append('\n');

        // First body line carries the status icon
        appendBodyLine(builder, first_body_with_icon, interior, is_error, p);
        for (int i = 1; i < body_lines.size(); i++) {
            String body_line = "  " + body_lines.get(i);
            appendBodyLine(builder, body_line, interior, is_error, p);
        }

        // Footer: ╰────────╯
        String footer_line = CORNER_BOTTOM_LEFT + repeat(HORIZONTAL, interior + 1) + CORNER_BOTTOM_RIGHT;
        builder.append(color_border(footer_line, is_error, p));
        return builder.toString();
    }

    private void appendBodyLine(StringBuilder builder, String content, int interior, boolean is_error, AnsiPalette p) {
        int padding = interior - visible_length(content);
        if (padding < 0) {
            padding = 0;
        }
        builder.append(color_border(VERTICAL, is_error, p));
        builder.append(' ');
        // Color the icon line specially when it carries the status glyph.
        if (content.startsWith("✓ ")) {
            builder.append(p.green("✓"));
            builder.append(content.substring(1));
        } else if (content.startsWith("✗ ")) {
            builder.append(p.red("✗"));
            builder.append(content.substring(1));
        } else {
            builder.append(content);
        }
        for (int i = 0; i < padding; i++) {
            builder.append(' ');
        }
        builder.append(color_border(VERTICAL, is_error, p));
        builder.append('\n');
    }

    private static String color_border(String text, boolean is_error, AnsiPalette palette) {
        return is_error ? palette.red(text) : palette.green(text);
    }

    private static int visible_length(String text) {
        return AnsiPalette.visible_width(text);
    }

    private static String repeat(String unit, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    /**
     * Break a single source line into chunks no wider than {@code max_width} visible characters.
     * Prefers a space boundary inside the window — falls back to a hard char-cut when the chunk
     * has no space (e.g. a long URL or an unbroken hash). Preserves empty lines as a single empty
     * chunk.
     */
    static List<String> soft_wrap(String line, int max_width) {
        List<String> chunks = new ArrayList<>();
        if (line == null) {
            chunks.add("");
            return chunks;
        }
        if (max_width <= 0 || visible_length(line) <= max_width) {
            chunks.add(line);
            return chunks;
        }
        int start = 0;
        int len = line.length();
        while (start < len) {
            int hard_end = Math.min(start + max_width, len);
            if (hard_end >= len) {
                chunks.add(line.substring(start));
                break;
            }
            // Prefer to break at the last space within [start, hard_end]. If no space exists,
            // hard-cut at the column boundary so we never produce a chunk wider than max_width.
            int space_break = line.lastIndexOf(' ', hard_end);
            int end;
            int next_start;
            if (space_break > start) {
                end = space_break;
                next_start = space_break + 1; // consume the space
            } else {
                end = hard_end;
                next_start = hard_end;
            }
            chunks.add(line.substring(start, end));
            start = next_start;
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }
}
