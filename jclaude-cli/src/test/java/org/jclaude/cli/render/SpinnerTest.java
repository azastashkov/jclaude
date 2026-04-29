package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Spinner}. Verifies the state machine ({@link Spinner#start},
 * {@link Spinner#tick}, {@link Spinner#stop}, success/failure variants) and that the rendered byte
 * stream contains the expected braille frame and label.
 */
final class SpinnerTest {

    @Test
    void start_marks_spinner_active_and_resets_frame_index() {
        Spinner spinner = new_spinner_with_buffer(new ByteArrayOutputStream());

        spinner.start("Working");

        assertThat(spinner.is_active()).isTrue();
        assertThat(spinner.frame_index()).isEqualTo(0);
        assertThat(spinner.current_label()).isEqualTo("Working");
    }

    @Test
    void tick_advances_frame_index_modulo_frame_count() {
        Spinner spinner = new_spinner_with_buffer(new ByteArrayOutputStream());
        spinner.start("Working");

        for (int i = 0; i < 12; i++) {
            spinner.tick();
        }

        // After 12 ticks on 10-frame braille set, index = 12 mod 10 = 2.
        assertThat(spinner.frame_index()).isEqualTo(2);
    }

    @Test
    void tick_is_noop_before_start() {
        Spinner spinner = new_spinner_with_buffer(new ByteArrayOutputStream());

        spinner.tick();

        assertThat(spinner.is_active()).isFalse();
        assertThat(spinner.frame_index()).isEqualTo(0);
    }

    @Test
    void stop_clears_state_and_marks_inactive() {
        Spinner spinner = new_spinner_with_buffer(new ByteArrayOutputStream());
        spinner.start("Working");
        spinner.tick();
        spinner.tick();

        spinner.stop();

        assertThat(spinner.is_active()).isFalse();
        assertThat(spinner.frame_index()).isEqualTo(0);
        assertThat(spinner.current_label()).isEmpty();
    }

    @Test
    void stop_with_success_writes_check_mark_and_label() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Spinner spinner = new_spinner_with_buffer(buffer);
        spinner.start("Reading");
        spinner.tick();

        spinner.stop_with_success("Done reading");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("✔ Done reading");
        assertThat(spinner.is_active()).isFalse();
    }

    @Test
    void stop_with_failure_writes_cross_mark_and_label() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Spinner spinner = new_spinner_with_buffer(buffer);
        spinner.start("Working");
        spinner.tick();

        spinner.stop_with_failure("crashed");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("✘ crashed");
    }

    @Test
    void rendered_frame_uses_braille_glyphs_when_colors_disabled() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Spinner spinner = new_spinner_with_buffer(buffer);

        spinner.start("Working");

        String output = buffer.toString(StandardCharsets.UTF_8);
        // First frame is the leading braille glyph.
        assertThat(output).contains("⠋ Working");
    }

    @Test
    void frame_for_index_wraps_around_via_modulo() {
        Spinner spinner = new_spinner_with_buffer(new ByteArrayOutputStream());

        String first = spinner.frame_for_index(0);
        String wrapped = spinner.frame_for_index(10);

        assertThat(first).isEqualTo(wrapped);
    }

    private static Spinner new_spinner_with_buffer(ByteArrayOutputStream buffer) {
        PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        return new Spinner(sink, AnsiPalette.with_color_disabled());
    }
}
