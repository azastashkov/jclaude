package org.jclaude.cli.render;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Streaming markdown-to-ANSI renderer.
 *
 * <p>Direct port of the Rust {@code TerminalRenderer} + {@code MarkdownStreamState} pair from
 * {@code rusty-claude-cli/src/render.rs}. Uses CommonMark-Java to parse buffered markdown chunks
 * into a node tree, then walks the tree emitting ANSI-styled text per the {@link AnsiPalette}.
 *
 * <p>Streaming model:
 *
 * <ul>
 *   <li>Callers feed partial markdown deltas to {@link #push(String)}; the renderer buffers until a
 *       safe boundary (blank line outside an open code fence) and then flushes the completed prefix
 *       as ANSI text.
 *   <li>{@link #flush()} emits whatever remains in the buffer, even if the trailing block is
 *       incomplete — used at end-of-stream.
 * </ul>
 *
 * <p>The renderer is stateless across {@link #render(String)} calls; the streaming API maintains
 * its own scratchpad.
 */
public final class MarkdownStreamRenderer {

    private final Parser parser;
    private final AnsiPalette palette;

    private final StringBuilder pending;

    public MarkdownStreamRenderer() {
        this(AnsiPalette.DEFAULT);
    }

    public MarkdownStreamRenderer(AnsiPalette palette) {
        this.parser = Parser.builder().build();
        this.palette = palette;
        this.pending = new StringBuilder();
    }

    /** Render a complete markdown document as a single ANSI-styled string. */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String normalized = normalize_nested_fences(markdown);
        Node document = parser.parse(normalized);
        AnsiVisitor visitor = new AnsiVisitor(palette);
        document.accept(visitor);
        String rendered = visitor.output();
        return rendered.replaceAll("\\s+$", "");
    }

    /**
     * Append {@code delta} to the streaming buffer and return any completed ANSI prefix that is
     * safe to emit. Returns {@code null} when the buffer has no completed block boundary yet.
     */
    public String push(String delta) {
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        pending.append(delta);
        int boundary = find_stream_safe_boundary(pending.toString());
        if (boundary < 0) {
            return null;
        }
        String ready = pending.substring(0, boundary);
        pending.delete(0, boundary);
        return render(ready);
    }

    /** Flush any remaining buffered markdown — used at end of stream. */
    public String flush() {
        if (pending.length() == 0) {
            return null;
        }
        String snapshot = pending.toString();
        pending.setLength(0);
        if (snapshot.trim().isEmpty()) {
            return null;
        }
        return render(snapshot);
    }

    /** Visible for tests — true when the streaming buffer is empty. */
    public boolean is_empty() {
        return pending.length() == 0;
    }

    /**
     * Return the smallest end-offset in {@code markdown} that completes at a blank line outside any
     * open fence, or {@code -1} when no safe boundary exists.
     *
     * <p>Mirrors Rust's {@code find_stream_safe_boundary}.
     */
    static int find_stream_safe_boundary(String markdown) {
        FenceMarker open_fence = null;
        int last_boundary = -1;

        int cursor = 0;
        int length = markdown.length();
        while (cursor < length) {
            int line_end = markdown.indexOf('\n', cursor);
            int line_terminator;
            String line;
            if (line_end < 0) {
                line = markdown.substring(cursor);
                line_terminator = length;
            } else {
                line = markdown.substring(cursor, line_end);
                line_terminator = line_end + 1;
            }

            if (open_fence != null) {
                if (line_closes_fence(line, open_fence)) {
                    open_fence = null;
                    last_boundary = line_terminator;
                }
            } else {
                FenceMarker opener = parse_fence_opener(line);
                if (opener != null) {
                    open_fence = opener;
                } else if (line.trim().isEmpty()) {
                    last_boundary = line_terminator;
                }
            }
            cursor = line_terminator;
        }
        return last_boundary;
    }

    /**
     * Pre-process raw markdown so that fenced code blocks whose body contains fence markers of
     * equal or greater length are wrapped with a longer fence. Mirrors Rust
     * {@code normalize_nested_fences}.
     */
    static String normalize_nested_fences(String markdown) {
        // Split the input into lines preserving terminators, mirroring Rust's split_inclusive.
        List<String> lines = new ArrayList<>();
        int cursor = 0;
        while (cursor < markdown.length()) {
            int next = markdown.indexOf('\n', cursor);
            if (next < 0) {
                lines.add(markdown.substring(cursor));
                break;
            }
            lines.add(markdown.substring(cursor, next + 1));
            cursor = next + 1;
        }

        // Classify every line by fence info.
        List<FenceLine> fence_info = new ArrayList<>(lines.size());
        for (String line : lines) {
            fence_info.add(parse_fence_line(line));
        }

        // Pair openers with closers and compute max inner-fence length per pair.
        List<int[]> pairs = new ArrayList<>();
        List<int[]> stack = new ArrayList<>();
        for (int i = 0; i < fence_info.size(); i++) {
            FenceLine fl = fence_info.get(i);
            if (fl == null) {
                continue;
            }
            if (fl.has_info) {
                stack.add(new int[] {i, fl.length, charToIndex(fl.character)});
                continue;
            }
            if (!stack.isEmpty()) {
                int[] top = stack.get(stack.size() - 1);
                int top_index = top[0];
                int top_length = top[1];
                FenceLine top_fence = fence_info.get(top_index);
                boolean closes_top =
                        top_fence != null && top_fence.character == fl.character && fl.length >= top_length;
                if (closes_top) {
                    stack.remove(stack.size() - 1);
                    int inner_max = 0;
                    for (int k = top_index + 1; k < i; k++) {
                        FenceLine inner = fence_info.get(k);
                        if (inner != null && inner.length > inner_max) {
                            inner_max = inner.length;
                        }
                    }
                    pairs.add(new int[] {top_index, i, inner_max});
                    continue;
                }
            }
            // Treat as opener.
            stack.add(new int[] {i, fl.length, charToIndex(fl.character)});
        }

        // Build rewrites for any pair whose opener was outgrown by a nested fence.
        boolean has_rewrites = false;
        Rewrite[] rewrites = new Rewrite[lines.size()];
        for (int[] pair : pairs) {
            int opener_idx = pair[0];
            int closer_idx = pair[1];
            int inner_max = pair[2];
            FenceLine opener_fl = fence_info.get(opener_idx);
            if (opener_fl == null || opener_fl.length > inner_max) {
                continue;
            }
            int new_len = inner_max + 1;
            rewrites[opener_idx] = new Rewrite(opener_fl.character, new_len, opener_fl.indent);
            FenceLine closer_fl = fence_info.get(closer_idx);
            if (closer_fl != null) {
                rewrites[closer_idx] = new Rewrite(closer_fl.character, new_len, closer_fl.indent);
            }
            has_rewrites = true;
        }

        if (!has_rewrites) {
            return markdown;
        }

        StringBuilder out = new StringBuilder(markdown.length() + 16);
        for (int i = 0; i < lines.size(); i++) {
            Rewrite rw = rewrites[i];
            if (rw == null) {
                out.append(lines.get(i));
                continue;
            }
            String line = lines.get(i);
            String trimmed = trimTrailingNewlineAndCarriageReturn(line);
            FenceLine fi = fence_info.get(i);
            int header_end = fi.indent + fi.length;
            String info = header_end <= trimmed.length() ? trimmed.substring(header_end) : "";
            String trailing = line.substring(trimmed.length());
            for (int j = 0; j < rw.indent; j++) {
                out.append(' ');
            }
            for (int j = 0; j < rw.new_len; j++) {
                out.append(rw.character);
            }
            out.append(info);
            out.append(trailing);
        }
        return out.toString();
    }

    private static int charToIndex(char ch) {
        return ch == '`' ? 0 : 1;
    }

    private static String trimTrailingNewlineAndCarriageReturn(String line) {
        int end = line.length();
        while (end > 0) {
            char ch = line.charAt(end - 1);
            if (ch == '\n' || ch == '\r') {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }

    private static FenceLine parse_fence_line(String line) {
        String trimmed = trimTrailingNewlineAndCarriageReturn(line);
        int indent = 0;
        while (indent < trimmed.length() && trimmed.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3) {
            return null;
        }
        if (indent >= trimmed.length()) {
            return null;
        }
        char ch = trimmed.charAt(indent);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int length = 0;
        while (indent + length < trimmed.length() && trimmed.charAt(indent + length) == ch) {
            length++;
        }
        if (length < 3) {
            return null;
        }
        String after = trimmed.substring(indent + length);
        if (ch == '`' && after.indexOf('`') >= 0) {
            return null;
        }
        boolean has_info = !after.trim().isEmpty();
        return new FenceLine(ch, length, has_info, indent);
    }

    private static FenceMarker parse_fence_opener(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3 || indent >= line.length()) {
            return null;
        }
        char ch = line.charAt(indent);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int length = 0;
        while (indent + length < line.length() && line.charAt(indent + length) == ch) {
            length++;
        }
        if (length < 3) {
            return null;
        }
        String info = line.substring(indent + length);
        if (ch == '`' && info.indexOf('`') >= 0) {
            return null;
        }
        return new FenceMarker(ch, length);
    }

    private static boolean line_closes_fence(String line, FenceMarker opener) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3) {
            return false;
        }
        int length = 0;
        while (indent + length < line.length() && line.charAt(indent + length) == opener.character) {
            length++;
        }
        if (length < opener.length) {
            return false;
        }
        for (int i = indent + length; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return true;
    }

    private record FenceLine(char character, int length, boolean has_info, int indent) {}

    private record FenceMarker(char character, int length) {}

    private record Rewrite(char character, int new_len, int indent) {}

    /** Walks the CommonMark node tree emitting ANSI-styled text. */
    private static final class AnsiVisitor extends AbstractVisitor {

        private final AnsiPalette palette;
        private final StringBuilder out = new StringBuilder();
        private int strong_depth;
        private int emphasis_depth;
        private int heading_level; // 0 = not in a heading
        private int blockquote_depth;
        private int ordered_index = -1; // -1 outside an ordered list
        private int list_depth;
        private boolean in_ordered_list;
        // Stack of list kinds (true=ordered)
        private final List<int[]> ordered_state = new ArrayList<>();

        AnsiVisitor(AnsiPalette palette) {
            this.palette = palette;
        }

        String output() {
            return out.toString();
        }

        @Override
        public void visit(Document document) {
            visitChildren(document);
        }

        @Override
        public void visit(Heading heading) {
            heading_level = heading.getLevel();
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            int start = out.length();
            visitChildren(heading);
            String body = out.substring(start);
            out.setLength(start);
            if (heading_level <= 2) {
                out.append(palette.heading(body));
            } else if (heading_level == 3) {
                out.append(palette.blue(body));
            } else {
                out.append(palette.grey(body));
            }
            heading_level = 0;
            out.append("\n\n");
        }

        @Override
        public void visit(Paragraph paragraph) {
            // List-item paragraphs are indented elsewhere; render normally otherwise.
            visitChildren(paragraph);
            out.append("\n\n");
        }

        @Override
        public void visit(BlockQuote block) {
            blockquote_depth++;
            out.append(palette.quote("│ "));
            int start = out.length();
            visitChildren(block);
            // Trim a trailing double-newline so consecutive paragraphs collapse
            String inner = out.substring(start);
            out.setLength(start);
            out.append(palette.quote(inner.replaceAll("\\n+$", "")));
            blockquote_depth--;
            out.append('\n');
        }

        @Override
        public void visit(BulletList list) {
            ordered_state.add(new int[] {0});
            list_depth++;
            visitChildren(list);
            list_depth--;
            ordered_state.remove(ordered_state.size() - 1);
            out.append('\n');
        }

        @Override
        public void visit(OrderedList list) {
            ordered_state.add(new int[] {(int) list.getMarkerStartNumber()});
            list_depth++;
            visitChildren(list);
            list_depth--;
            ordered_state.remove(ordered_state.size() - 1);
            out.append('\n');
        }

        @Override
        public void visit(ListItem item) {
            int depth = list_depth - 1;
            if (depth < 0) {
                depth = 0;
            }
            for (int i = 0; i < depth; i++) {
                out.append("  ");
            }
            int[] state = ordered_state.isEmpty() ? null : ordered_state.get(ordered_state.size() - 1);
            String marker;
            if (state != null && state[0] > 0) {
                marker = state[0] + ". ";
                state[0]++;
            } else {
                marker = "• ";
            }
            out.append(marker);
            // Walk children; suppress paragraph block-trailing newlines so list items remain compact.
            int start = out.length();
            visitChildren(item);
            String body = out.substring(start);
            out.setLength(start);
            out.append(body.replaceAll("\\n\\n$", "\n"));
            if (!body.endsWith("\n")) {
                out.append('\n');
            }
        }

        @Override
        public void visit(Emphasis emphasis) {
            emphasis_depth++;
            int start = out.length();
            visitChildren(emphasis);
            String inner = out.substring(start);
            out.setLength(start);
            out.append(palette.emphasis(inner));
            emphasis_depth--;
        }

        @Override
        public void visit(StrongEmphasis strong) {
            strong_depth++;
            int start = out.length();
            visitChildren(strong);
            String inner = out.substring(start);
            out.setLength(start);
            out.append(palette.strong(inner));
            strong_depth--;
        }

        @Override
        public void visit(Code code) {
            out.append(palette.inline_code("`" + code.getLiteral() + "`"));
        }

        @Override
        public void visit(FencedCodeBlock fenced) {
            String lang = fenced.getInfo() == null || fenced.getInfo().isBlank()
                    ? "code"
                    : fenced.getInfo().trim();
            out.append(palette.code_border("╭─ " + lang)).append('\n');
            String body = fenced.getLiteral() == null ? "" : fenced.getLiteral();
            out.append(body);
            if (!body.endsWith("\n")) {
                out.append('\n');
            }
            out.append(palette.code_border("╰─")).append("\n\n");
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            out.append(palette.code_border("╭─ code")).append('\n');
            String body = block.getLiteral() == null ? "" : block.getLiteral();
            out.append(body);
            if (!body.endsWith("\n")) {
                out.append('\n');
            }
            out.append(palette.code_border("╰─")).append("\n\n");
        }

        @Override
        public void visit(ThematicBreak rule) {
            out.append("---\n");
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            out.append('\n');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            out.append('\n');
        }

        @Override
        public void visit(Link link) {
            String dest = link.getDestination();
            int start = out.length();
            visitChildren(link);
            String label = out.substring(start);
            out.setLength(start);
            String stripped = AnsiPalette.strip_ansi(label);
            String text = stripped.isEmpty() ? dest : stripped;
            out.append(palette.link("[" + text + "](" + dest + ")"));
        }

        @Override
        public void visit(Image image) {
            String dest = image.getDestination();
            out.append(palette.link("[image:" + dest + "]"));
        }

        @Override
        public void visit(HtmlBlock html) {
            String literal = html.getLiteral() == null ? "" : html.getLiteral();
            out.append(literal);
        }

        @Override
        public void visit(HtmlInline html) {
            String literal = html.getLiteral() == null ? "" : html.getLiteral();
            out.append(literal);
        }

        @Override
        public void visit(Text text) {
            String literal = text.getLiteral();
            String styled = literal;
            if (heading_level > 0) {
                // styling for heading is applied at the heading level
                out.append(styled);
                return;
            }
            if (strong_depth > 0 || emphasis_depth > 0) {
                // styling applied at parent level
                out.append(styled);
                return;
            }
            out.append(styled);
        }
    }
}
