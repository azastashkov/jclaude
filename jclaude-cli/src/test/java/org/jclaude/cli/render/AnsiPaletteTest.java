package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnsiPalette}. The contract covers:
 *
 * <ul>
 *   <li>{@code NO_COLOR} env var disables every wrapper method.
 *   <li>Non-TTY (no console) disables every wrapper method.
 *   <li>Strip-ANSI helper removes CSI escape sequences correctly.
 *   <li>Each named color emits the expected escape prefix.
 * </ul>
 */
final class AnsiPaletteTest {

    @Test
    void colors_enabled_when_no_no_color_env_and_tty() {
        AnsiPalette palette = AnsiPalette.of(null, true);

        assertThat(palette.colors_enabled()).isTrue();
    }

    @Test
    void colors_disabled_when_no_color_env_set() {
        AnsiPalette palette = AnsiPalette.of("1", true);

        assertThat(palette.colors_enabled()).isFalse();
        assertThat(palette.red("hello")).isEqualTo("hello");
        assertThat(palette.bold("bold")).isEqualTo("bold");
    }

    @Test
    void colors_disabled_when_stdout_is_not_a_tty() {
        AnsiPalette palette = AnsiPalette.of(null, false);

        assertThat(palette.colors_enabled()).isFalse();
        assertThat(palette.green("ok")).isEqualTo("ok");
    }

    @Test
    void empty_no_color_env_does_not_disable_colors() {
        AnsiPalette palette = AnsiPalette.of("", true);

        assertThat(palette.colors_enabled()).isTrue();
    }

    @Test
    void each_named_color_emits_its_escape_prefix() {
        AnsiPalette palette = AnsiPalette.with_color_enabled();

        assertThat(palette.red("x")).isEqualTo(AnsiPalette.RED + "x" + AnsiPalette.RESET);
        assertThat(palette.green("x")).isEqualTo(AnsiPalette.GREEN + "x" + AnsiPalette.RESET);
        assertThat(palette.yellow("x")).isEqualTo(AnsiPalette.YELLOW + "x" + AnsiPalette.RESET);
        assertThat(palette.blue("x")).isEqualTo(AnsiPalette.BLUE + "x" + AnsiPalette.RESET);
        assertThat(palette.magenta("x")).isEqualTo(AnsiPalette.MAGENTA + "x" + AnsiPalette.RESET);
        assertThat(palette.cyan("x")).isEqualTo(AnsiPalette.CYAN + "x" + AnsiPalette.RESET);
        assertThat(palette.dim("x")).isEqualTo(AnsiPalette.DIM + "x" + AnsiPalette.RESET);
        assertThat(palette.bold("x")).isEqualTo(AnsiPalette.BOLD + "x" + AnsiPalette.RESET);
    }

    @Test
    void heading_strong_emphasis_use_bold_and_italic_combinators() {
        AnsiPalette palette = AnsiPalette.with_color_enabled();

        assertThat(palette.heading("h"))
                .startsWith(AnsiPalette.BOLD + AnsiPalette.CYAN)
                .endsWith(AnsiPalette.RESET)
                .contains("h");
        assertThat(palette.strong("s")).startsWith(AnsiPalette.BOLD + AnsiPalette.YELLOW);
        assertThat(palette.emphasis("e")).startsWith(AnsiPalette.ITALIC + AnsiPalette.MAGENTA);
        assertThat(palette.link("l")).startsWith(AnsiPalette.UNDERLINE + AnsiPalette.BLUE);
    }

    @Test
    void paint_with_null_text_returns_empty_string() {
        AnsiPalette palette = AnsiPalette.with_color_enabled();

        assertThat(palette.paint(AnsiPalette.RED, null)).isEqualTo("");
    }

    @Test
    void strip_ansi_removes_csi_escape_sequences() {
        String painted =
                AnsiPalette.RED + "alpha" + AnsiPalette.RESET + " " + AnsiPalette.BOLD + "beta" + AnsiPalette.RESET;

        assertThat(AnsiPalette.strip_ansi(painted)).isEqualTo("alpha beta");
    }

    @Test
    void strip_ansi_handles_null_and_empty() {
        assertThat(AnsiPalette.strip_ansi(null)).isEqualTo("");
        assertThat(AnsiPalette.strip_ansi("")).isEqualTo("");
    }

    @Test
    void visible_width_counts_codepoints_after_stripping() {
        String painted = AnsiPalette.GREEN + "abc" + AnsiPalette.RESET;

        assertThat(AnsiPalette.visible_width(painted)).isEqualTo(3);
    }

    @Test
    void with_color_disabled_emits_plain_text() {
        AnsiPalette palette = AnsiPalette.with_color_disabled();

        assertThat(palette.colors_enabled()).isFalse();
        assertThat(palette.heading("h")).isEqualTo("h");
        assertThat(palette.strong("s")).isEqualTo("s");
    }
}
