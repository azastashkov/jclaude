package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies {@link TerminalRenderer#truncate_for_display} for in-REPL display. */
final class TerminalRendererTest {

    @Test
    void short_output_is_returned_verbatim() {
        String input = "line1\nline2\nline3";
        String output = TerminalRenderer.truncate_for_display(input, 10);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void exactly_max_lines_is_returned_verbatim() {
        String input = "a\nb\nc";
        String output = TerminalRenderer.truncate_for_display(input, 3);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void over_max_lines_truncates_with_footer() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            if (i > 1) builder.append('\n');
            builder.append("line").append(i);
        }
        String output = TerminalRenderer.truncate_for_display(builder.toString(), 40);

        // Kept lines plus footer.
        String[] kept = output.split("\n");
        assertThat(kept).hasSize(41);
        assertThat(kept[0]).isEqualTo("line1");
        assertThat(kept[39]).isEqualTo("line40");
        assertThat(kept[40]).isEqualTo("… (10 more lines)");
    }

    @Test
    void single_long_line_passes_through_untruncated() {
        // Truncation is line-based; soft-wrap inside ToolResultBox handles single-line width.
        String input = "x".repeat(500);
        String output = TerminalRenderer.truncate_for_display(input, 40);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void max_lines_zero_or_negative_passes_through() {
        assertThat(TerminalRenderer.truncate_for_display("a\nb\nc", 0)).isEqualTo("a\nb\nc");
        assertThat(TerminalRenderer.truncate_for_display("a\nb\nc", -1)).isEqualTo("a\nb\nc");
    }

    @Test
    void null_input_passes_through() {
        assertThat(TerminalRenderer.truncate_for_display(null, 10)).isNull();
    }
}
