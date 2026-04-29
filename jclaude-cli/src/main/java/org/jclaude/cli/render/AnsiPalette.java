package org.jclaude.cli.render;

import java.io.PrintStream;
import java.util.Locale;

/**
 * ANSI color and style escape constants used by the streaming markdown renderer, spinner widget,
 * and tool-result boxes.
 *
 * <p>Mirrors the {@code ColorTheme} struct from
 * {@code rusty-claude-cli/src/render.rs} (heading=cyan, emphasis=magenta, strong=yellow, etc).
 *
 * <p>Color emission is automatically suppressed when:
 *
 * <ul>
 *   <li>The {@code NO_COLOR} environment variable is present and non-empty
 *       (see <a href="https://no-color.org">no-color.org</a>), or
 *   <li>{@link System#console()} returns {@code null}, signaling that stdout is not attached to a
 *       TTY (piped, redirected, or running under a build/CI harness).
 * </ul>
 *
 * <p>Use {@link #colors_enabled()} to check the runtime decision and {@link #with_color_disabled()}
 * to force a non-coloring palette in tests.
 */
public final class AnsiPalette {

    /** ANSI control sequences are introduced by ESC + {@code [}. */
    public static final String ESC = "[";

    public static final String RESET = ESC + "0m";
    public static final String BOLD = ESC + "1m";
    public static final String DIM = ESC + "2m";
    public static final String ITALIC = ESC + "3m";
    public static final String UNDERLINE = ESC + "4m";

    public static final String RED = ESC + "31m";
    public static final String GREEN = ESC + "32m";
    public static final String YELLOW = ESC + "33m";
    public static final String BLUE = ESC + "34m";
    public static final String MAGENTA = ESC + "35m";
    public static final String CYAN = ESC + "36m";
    public static final String WHITE = ESC + "37m";
    public static final String GREY = ESC + "90m";

    /** Cached default palette honoring {@code NO_COLOR} and TTY status from process startup. */
    public static final AnsiPalette DEFAULT = detect_default();

    private final boolean colors_enabled;

    private AnsiPalette(boolean colors_enabled) {
        this.colors_enabled = colors_enabled;
    }

    /**
     * Build a palette that respects the supplied {@code no_color} env var and the TTY hint. When
     * {@code no_color} is non-{@code null} and non-empty, colors are disabled. When {@code is_tty}
     * is {@code false}, colors are also disabled.
     */
    public static AnsiPalette of(String no_color_env, boolean is_tty) {
        boolean disabled = (no_color_env != null && !no_color_env.isEmpty()) || !is_tty;
        return new AnsiPalette(!disabled);
    }

    /** Convenience for tests / non-TTY environments — always returns plain text. */
    public static AnsiPalette with_color_disabled() {
        return new AnsiPalette(false);
    }

    /** Convenience for unit tests that need to assert ANSI bytes — always returns escapes. */
    public static AnsiPalette with_color_enabled() {
        return new AnsiPalette(true);
    }

    public boolean colors_enabled() {
        return colors_enabled;
    }

    /** Wrap {@code text} with the supplied ANSI prefix and a {@link #RESET} suffix. */
    public String paint(String prefix, String text) {
        if (!colors_enabled || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return prefix + text + RESET;
    }

    public String bold(String text) {
        return paint(BOLD, text);
    }

    public String dim(String text) {
        return paint(DIM, text);
    }

    public String italic(String text) {
        return paint(ITALIC, text);
    }

    public String underline(String text) {
        return paint(UNDERLINE, text);
    }

    public String red(String text) {
        return paint(RED, text);
    }

    public String green(String text) {
        return paint(GREEN, text);
    }

    public String yellow(String text) {
        return paint(YELLOW, text);
    }

    public String blue(String text) {
        return paint(BLUE, text);
    }

    public String magenta(String text) {
        return paint(MAGENTA, text);
    }

    public String cyan(String text) {
        return paint(CYAN, text);
    }

    public String grey(String text) {
        return paint(GREY, text);
    }

    /**
     * Heading color for level 1/2 headings. Matches the Rust {@code ColorTheme.heading} (cyan).
     */
    public String heading(String text) {
        return paint(BOLD + CYAN, text);
    }

    /** Strong (bold) emphasis — yellow per the Rust theme. */
    public String strong(String text) {
        return paint(BOLD + YELLOW, text);
    }

    /** Italic emphasis — magenta per the Rust theme. */
    public String emphasis(String text) {
        return paint(ITALIC + MAGENTA, text);
    }

    /** Inline code — green per the Rust theme. */
    public String inline_code(String text) {
        return paint(GREEN, text);
    }

    /** Block-quote prefix — dim grey. */
    public String quote(String text) {
        return paint(DIM + GREY, text);
    }

    /** Underlined link — blue per the Rust theme. */
    public String link(String text) {
        return paint(UNDERLINE + BLUE, text);
    }

    /** Code-block border — dim grey. */
    public String code_border(String text) {
        return paint(DIM + GREY, text);
    }

    /** Syntax-highlight: comment (dim grey). */
    public String code_comment(String text) {
        return paint(DIM + GREY, text);
    }

    /** Syntax-highlight: string literal (green). */
    public String code_string(String text) {
        return paint(GREEN, text);
    }

    /** Syntax-highlight: number literal (magenta). */
    public String code_number(String text) {
        return paint(MAGENTA, text);
    }

    /** Syntax-highlight: language keyword (cyan). */
    public String code_keyword(String text) {
        return paint(CYAN, text);
    }

    /** Syntax-highlight: type or built-in name (yellow). */
    public String code_type(String text) {
        return paint(YELLOW, text);
    }

    /** Table border — dim cyan per the Rust theme. */
    public String table_border(String text) {
        return paint(DIM + CYAN, text);
    }

    /** Spinner active frame — blue. */
    public String spinner_active(String text) {
        return paint(BLUE, text);
    }

    /** Spinner success indicator — green. */
    public String spinner_done(String text) {
        return paint(GREEN, text);
    }

    /** Spinner failure indicator — red. */
    public String spinner_failed(String text) {
        return paint(RED, text);
    }

    /**
     * Strip ANSI sequences from the supplied string. Mirrors the Rust {@code strip_ansi} helper
     * used by the table-rendering code to compute visible widths.
     */
    public static String strip_ansi(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        StringBuilder builder = new StringBuilder(input.length());
        int index = 0;
        int length = input.length();
        while (index < length) {
            char ch = input.charAt(index);
            if (ch == 0x1b && index + 1 < length && input.charAt(index + 1) == '[') {
                index += 2;
                while (index < length) {
                    char next = input.charAt(index);
                    index += 1;
                    // CSI sequences are terminated by a final byte in the range 0x40-0x7E.
                    if ((next >= 'A' && next <= 'Z') || (next >= 'a' && next <= 'z')) {
                        break;
                    }
                }
            } else {
                builder.append(ch);
                index += 1;
            }
        }
        return builder.toString();
    }

    /** Return the visible character width of a string after stripping ANSI escapes. */
    public static int visible_width(String input) {
        return strip_ansi(input).length();
    }

    /**
     * Print {@code message} to {@code sink} ending with a newline, applying the supplied prefix
     * only when colors are enabled. Otherwise prints the plain text.
     */
    public void println(PrintStream sink, String prefix, String message) {
        sink.println(paint(prefix, message));
    }

    private static AnsiPalette detect_default() {
        String no_color = System.getenv("NO_COLOR");
        // System.console() is non-null when stdout is attached to a TTY in interactive mode.
        boolean is_tty = System.console() != null;
        // The CLI is normally distributed wrapped — look for jline's NonInteractive marker too.
        String term = System.getenv("TERM");
        boolean dumb_terminal = term != null && term.toLowerCase(Locale.ROOT).equals("dumb");
        return of(no_color, is_tty && !dumb_terminal);
    }
}
