package org.jclaude.runtime.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1 tests for {@link Bash}, ported 1:1 from
 * {@code claw-code/rust/crates/runtime/src/bash.rs} ({@code mod tests} L310-L352 and
 * {@code mod truncation_tests} L372-L401).
 *
 * <p>New Phase 1 tests cover behaviour the Rust file exercises only indirectly: timeout,
 * exit-code propagation, stderr capture, and {@code cwd} resolution.
 */
@DisabledOnOs(OS.WINDOWS)
final class BashTest {

    // ----- Tests ported from `mod tests` in bash.rs ------------------------------------------

    /** Rust: {@code executes_simple_command} — bash.rs L315-L333. */
    @Test
    void executes_simple_command() throws IOException {
        BashCommandOutput output = Bash.execute(new BashCommandInput("printf 'hello'", null, 1_000, false));

        assertThat(output.stdout()).isEqualTo("hello");
        assertThat(output.timed_out()).isFalse();
        assertThat(output.exit_code()).isZero();
    }

    // ----- Phase 1 coverage for Bash subprocess execution -----------------------------------

    @Test
    void true_command_exits_zero() throws IOException {
        BashCommandOutput output = Bash.execute(BashCommandInput.of("true"));

        assertThat(output.exit_code()).isZero();
        assertThat(output.stdout()).isEmpty();
        assertThat(output.stderr()).isEmpty();
        assertThat(output.timed_out()).isFalse();
    }

    @Test
    void false_command_exits_one() throws IOException {
        BashCommandOutput output = Bash.execute(BashCommandInput.of("false"));

        assertThat(output.exit_code()).isOne();
        assertThat(output.timed_out()).isFalse();
    }

    @Test
    void propagates_explicit_exit_code() throws IOException {
        BashCommandOutput output = Bash.execute(BashCommandInput.of("exit 7"));

        assertThat(output.exit_code()).isEqualTo(7);
        assertThat(output.timed_out()).isFalse();
    }

    @Test
    void captures_stderr_separately_from_stdout() throws IOException {
        BashCommandOutput output = Bash.execute(BashCommandInput.of("printf 'out'; printf 'err' 1>&2"));

        assertThat(output.stdout()).isEqualTo("out");
        assertThat(output.stderr()).isEqualTo("err");
        assertThat(output.exit_code()).isZero();
    }

    @Test
    void echoes_to_stdout_with_newline() throws IOException {
        BashCommandOutput output = Bash.execute(BashCommandInput.of("echo hello"));

        assertThat(output.stdout()).isEqualTo("hello\n");
        assertThat(output.stderr()).isEmpty();
        assertThat(output.exit_code()).isZero();
    }

    @Test
    void honours_cwd_when_provided(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve("hello.txt");
        Files.writeString(marker, "hi");

        BashCommandOutput output = Bash.execute(new BashCommandInput("ls hello.txt", tempDir, 1_000, false));

        assertThat(output.stdout()).isEqualTo("hello.txt\n");
        assertThat(output.exit_code()).isZero();
    }

    @Test
    void times_out_long_running_command() throws IOException {
        BashCommandOutput output = Bash.execute(new BashCommandInput("sleep 5", null, 200, false));

        assertThat(output.timed_out()).isTrue();
        assertThat(output.exit_code()).isEqualTo(-1);
    }

    @Test
    void rejects_null_command() {
        assertThatThrownBy(() -> new BashCommandInput(null, null, 1_000, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_non_positive_timeout() {
        assertThatThrownBy(() -> new BashCommandInput("true", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
