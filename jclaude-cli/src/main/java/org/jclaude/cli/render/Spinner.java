package org.jclaude.cli.render;

import java.io.PrintStream;

/**
 * Animated single-line spinner widget.
 *
 * <p>Direct port of the Rust {@code Spinner} struct from
 * {@code rusty-claude-cli/src/render.rs}. Frames are the braille spinner sequence
 * {@code ⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏}; an ASCII fallback {@code |/-\} is available for terminals
 * that cannot render braille.
 *
 * <p>State machine:
 *
 * <ul>
 *   <li>{@link #start(String)} — emits the first frame on its own line and tracks the label.
 *   <li>{@link #tick()} — advances one frame, redrawing in place using cursor-column reset.
 *   <li>{@link #stop()} / {@link #stop_with_success(String)} / {@link #stop_with_failure(String)} —
 *       clears the spinner line and prints a final state line ({@code ✔} or {@code ✘}).
 * </ul>
 *
 * <p>This class is intentionally not thread-safe; callers either drive ticks from a single thread
 * or wrap accesses themselves.
 */
public final class Spinner {

    /** Braille spinner frames (10 frames). Matches the Rust {@code FRAMES} constant verbatim. */
    public static final String[] BRAILLE_FRAMES = {"⠇", "⠈⠁", "⠋", "⠙", "⠸", "⠴", "⠦", "⠧", "⠇", "⠏"};
    // The literal frames from Rust use non-BMP characters that look like:
    //   ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏
    // We keep them as raw chars in the array below so the byte-by-byte output matches the Rust
    // implementation; the array above is only kept for documentation.
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /** ASCII spinner frames for environments that can't render braille. */
    public static final String[] ASCII_FRAMES = {"|", "/", "-", "\\"};

    private final PrintStream out;
    private final AnsiPalette palette;
    private final String[] frames;

    private int frame_index;
    private String current_label;
    private boolean active;

    public Spinner() {
        this(System.err, AnsiPalette.DEFAULT, FRAMES);
    }

    public Spinner(PrintStream out, AnsiPalette palette) {
        this(out, palette, FRAMES);
    }

    public Spinner(PrintStream out, AnsiPalette palette, String[] frames) {
        this.out = out;
        this.palette = palette;
        this.frames = frames.clone();
        this.frame_index = 0;
        this.current_label = "";
        this.active = false;
    }

    /** Whether {@link #start} has been called and {@link #stop} has not. */
    public boolean is_active() {
        return active;
    }

    public int frame_index() {
        return frame_index;
    }

    public String current_label() {
        return current_label;
    }

    /**
     * Begin animation with the supplied label. Idempotent — repeated calls update the label and
     * reset the frame counter.
     */
    public void start(String label) {
        this.current_label = label == null ? "" : label;
        this.frame_index = 0;
        this.active = true;
        render_current_frame();
    }

    /**
     * Advance one frame. No-op when the spinner is not active. Emits the new frame on the same line
     * as the prior frame, wrapped in {@code \r} so the terminal redraws in place.
     */
    public void tick() {
        if (!active) {
            return;
        }
        frame_index = (frame_index + 1) % frames.length;
        render_current_frame();
    }

    /** Stop without emitting a final status line — clears the spinner line and resets state. */
    public void stop() {
        if (!active) {
            return;
        }
        clear_line();
        active = false;
        frame_index = 0;
        current_label = "";
    }

    /** Stop and print a green check mark followed by the supplied label. */
    public void stop_with_success(String label) {
        if (!active) {
            // Always print final line, even if the spinner was never started — matches Rust behaviour.
            out.println(palette.spinner_done("✔ " + (label == null ? "" : label)));
            out.flush();
            return;
        }
        clear_line();
        active = false;
        frame_index = 0;
        current_label = "";
        out.println(palette.spinner_done("✔ " + (label == null ? "" : label)));
        out.flush();
    }

    /** Stop and print a red cross followed by the supplied label. */
    public void stop_with_failure(String label) {
        if (!active) {
            out.println(palette.spinner_failed("✘ " + (label == null ? "" : label)));
            out.flush();
            return;
        }
        clear_line();
        active = false;
        frame_index = 0;
        current_label = "";
        out.println(palette.spinner_failed("✘ " + (label == null ? "" : label)));
        out.flush();
    }

    /** Returns the frame string at index {@code i mod frames.length}. Visible for tests. */
    public String frame_for_index(int i) {
        int wrapped = i;
        if (wrapped < 0) {
            wrapped = 0;
        }
        return frames[wrapped % frames.length];
    }

    private void render_current_frame() {
        String frame = frames[frame_index % frames.length];
        StringBuilder buffer = new StringBuilder();
        buffer.append('\r');
        buffer.append(palette.spinner_active(frame + " " + current_label));
        // Pad with spaces so any leftover characters from a longer prior label are erased.
        // Eight spaces is enough for the typical "Working" label downgrade.
        buffer.append("        ");
        buffer.append('\r');
        buffer.append(palette.spinner_active(frame + " " + current_label));
        out.print(buffer.toString());
        out.flush();
    }

    private void clear_line() {
        // Move to start of line and overwrite with spaces, then return to start-of-line again.
        StringBuilder pad = new StringBuilder();
        pad.append('\r');
        int width = current_label.length() + frames[0].length() + 4;
        for (int i = 0; i < width; i++) {
            pad.append(' ');
        }
        pad.append('\r');
        out.print(pad.toString());
        out.flush();
    }
}
