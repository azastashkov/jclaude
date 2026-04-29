package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolResultBox}. Verifies the box-drawing borders, status icons, and
 * green/red color flag behavior matches the Rust render.
 */
final class ToolResultBoxTest {

    @Test
    void successful_result_renders_with_check_mark_and_green_corners() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render("read_file", "alpha", false);

        assertThat(rendered).contains("╭─ read_file ");
        assertThat(rendered).contains("╮");
        assertThat(rendered).contains("│ ✓ alpha");
        assertThat(rendered).endsWith("╯");
    }

    @Test
    void error_result_renders_with_cross_mark() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render("bash", "command not found", true);

        assertThat(rendered).contains("╭─ bash ");
        assertThat(rendered).contains("✗ command not found");
    }

    @Test
    void multi_line_output_creates_one_box_row_per_line() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render("grep", "alpha\nbeta\ngamma", false);

        String[] lines = rendered.split("\n");
        // 1 header + 3 body + 1 footer = 5 lines
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).startsWith("╭─");
        assertThat(lines[1]).contains("✓ alpha");
        assertThat(lines[2]).contains("beta");
        assertThat(lines[3]).contains("gamma");
        assertThat(lines[4]).startsWith("╰─");
    }

    @Test
    void box_width_accommodates_longest_visible_line() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render("tool", "x\nlonger line of output\ny", false);

        for (String line : rendered.split("\n")) {
            // Every line begins with a corner or the side border
            assertThat(line.startsWith("╭") || line.startsWith("│") || line.startsWith("╰"))
                    .as("line: %s", line)
                    .isTrue();
        }
    }

    @Test
    void empty_output_renders_a_single_status_row() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render("noop", "", false);

        assertThat(rendered).contains("╭─ noop");
        assertThat(rendered).contains("│ ✓");
    }

    @Test
    void color_enabled_palette_emits_red_borders_for_errors() {
        AnsiPalette palette = AnsiPalette.with_color_enabled();
        ToolResultBox box = new ToolResultBox(palette);

        String rendered = box.render("bash", "boom", true);

        assertThat(rendered).contains(AnsiPalette.RED + "╭");
    }

    @Test
    void color_enabled_palette_emits_green_borders_for_success() {
        AnsiPalette palette = AnsiPalette.with_color_enabled();
        ToolResultBox box = new ToolResultBox(palette);

        String rendered = box.render("read_file", "ok", false);

        assertThat(rendered).contains(AnsiPalette.GREEN + "╭");
    }

    @Test
    void null_tool_name_and_output_are_safe() {
        ToolResultBox box = new ToolResultBox(AnsiPalette.with_color_disabled());

        String rendered = box.render(null, null, false);

        assertThat(rendered).contains("╭");
        assertThat(rendered).contains("╯");
    }
}
