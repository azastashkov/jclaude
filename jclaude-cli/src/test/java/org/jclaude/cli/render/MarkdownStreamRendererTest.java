package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MarkdownStreamRenderer}. Mirrors the Rust suite in
 * {@code crates/rusty-claude-cli/src/render.rs}: heading/list/inline-code, fenced code blocks,
 * streaming buffer that holds incomplete blocks, and nested-fence normalization.
 */
final class MarkdownStreamRendererTest {

    @Test
    void renders_heading_and_inline_styles_with_ansi_escapes() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_enabled());

        String output = renderer.render("# Heading\n\nThis is **bold** and *italic*.\n\n- item\n\n`code`");

        assertThat(output).contains("Heading");
        assertThat(output).contains("• item");
        assertThat(output).contains("`code`");
        assertThat(output).contains("[");
    }

    @Test
    void renders_links_with_blue_underline_and_keeps_destination() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_enabled());

        String output = renderer.render("See [Claw](https://example.com/docs) now.");
        String stripped = AnsiPalette.strip_ansi(output);

        assertThat(stripped).contains("[Claw](https://example.com/docs)");
        assertThat(output).contains("[");
    }

    @Test
    void renders_fenced_code_block_with_box_borders_and_language_label() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        String output = renderer.render("```rust\nfn hi() { println!(\"hi\"); }\n```");

        assertThat(output).contains("╭─ rust");
        assertThat(output).contains("fn hi");
        assertThat(output).contains("╰─");
    }

    @Test
    void renders_ordered_and_nested_lists_with_indentation() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        String output = renderer.render("1. first\n2. second\n   - nested\n   - child");

        assertThat(output).contains("1. first");
        assertThat(output).contains("2. second");
        assertThat(output).contains("• nested");
        assertThat(output).contains("• child");
    }

    @Test
    void streaming_buffer_holds_incomplete_block_until_blank_line() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.push("# Heading")).isNull();
        String flushed = renderer.push("\n\nParagraph\n\n");

        assertThat(flushed).isNotNull();
        assertThat(flushed).contains("Heading");
        assertThat(flushed).contains("Paragraph");
    }

    @Test
    void streaming_buffer_holds_open_code_fence_until_close() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.push("```rust\nfn main() {}\n")).isNull();

        String flushed = renderer.push("```\n");
        assertThat(flushed).isNotNull();
        assertThat(flushed).contains("fn main()");
    }

    @Test
    void streaming_buffer_recognizes_nested_fence_with_outer_four_backticks() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.push("````markdown\n```rust\nfn inner() {}\n")).isNull();
        assertThat(renderer.push("```\n")).isNull();

        String flushed = renderer.push("````\n");
        assertThat(flushed).isNotNull();
        assertThat(flushed).contains("fn inner()");
    }

    @Test
    void streaming_buffer_distinguishes_backtick_and_tilde_fences() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.push("~~~text\n")).isNull();
        assertThat(renderer.push("```\nstill inside tilde fence\n")).isNull();
        assertThat(renderer.push("```\n")).isNull();

        String flushed = renderer.push("~~~\n");
        assertThat(flushed).isNotNull();
        assertThat(flushed).contains("still inside tilde fence");
    }

    @Test
    void flush_returns_pending_buffer_at_end_of_stream() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());
        renderer.push("# trailing heading");

        String flushed = renderer.flush();

        assertThat(flushed).isNotNull();
        assertThat(flushed).contains("trailing heading");
    }

    @Test
    void flush_returns_null_when_buffer_empty() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.flush()).isNull();
    }

    @Test
    void render_empty_input_returns_empty_string() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        assertThat(renderer.render("")).isEmpty();
        assertThat(renderer.render(null)).isEmpty();
    }

    @Test
    void render_thematic_break_yields_horizontal_rule_marker() {
        MarkdownStreamRenderer renderer = new MarkdownStreamRenderer(AnsiPalette.with_color_disabled());

        String output = renderer.render("text\n\n---\n\nmore text");

        assertThat(output).contains("---");
        assertThat(output).contains("text");
        assertThat(output).contains("more text");
    }

    @Test
    void normalize_nested_fences_preserves_inner_markers() {
        String input = "````markdown\n```rust\nfn nested() {}\n```\n````";

        String normalized = MarkdownStreamRenderer.normalize_nested_fences(input);

        // Outer fence remains four-backtick (or longer); inner triple-backtick is preserved as text.
        assertThat(normalized).contains("```rust");
        assertThat(normalized).contains("fn nested()");
    }

    @Test
    void find_stream_safe_boundary_returns_blank_line_offset() {
        String input = "first paragraph\n\n";

        int boundary = MarkdownStreamRenderer.find_stream_safe_boundary(input);

        // Boundary is the offset just past the blank line.
        assertThat(boundary).isEqualTo(input.length());
    }

    @Test
    void find_stream_safe_boundary_returns_negative_inside_open_fence() {
        String input = "```rust\nfn a() {}\n";

        int boundary = MarkdownStreamRenderer.find_stream_safe_boundary(input);

        assertThat(boundary).isEqualTo(-1);
    }
}
