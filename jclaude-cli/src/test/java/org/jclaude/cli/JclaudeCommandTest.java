package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Picocli arg-parsing tests for {@link JclaudeCommand}. Mirrors the {@code parse_args} test cluster
 * around crates/rusty-claude-cli/src/main.rs:9686 ({@code parses_prompt_subcommand}, {@code
 * parses_bare_prompt_and_json_output_flag}, {@code parses_compact_flag_for_prompt_mode}, {@code
 * resolves_model_aliases_in_args}, {@code parses_permission_mode_flag}, {@code
 * parses_allowed_tools_flags_with_aliases_and_lists}).
 *
 * <p>The Java MVP CLI exposes a single {@code jclaude} entrypoint via picocli. The Rust harness's
 * notion of a separate {@code prompt} subcommand maps to the bare positional/{@code -p} surface here.
 */
final class JclaudeCommandTest {

    @Test
    void parses_bare_positional_prompt() {
        // Mirrors `parses_prompt_subcommand` —
        // crates/rusty-claude-cli/src/main.rs:9686.
        JclaudeCommand command = parse_args("hello", "world");

        assertThat(command.positional_prompt()).containsExactly("hello", "world");
        assertThat(command.dash_p_prompt()).isNull();
    }

    @Test
    void parses_dash_p_prompt_flag() {
        JclaudeCommand command = parse_args("-p", "explain this");

        assertThat(command.dash_p_prompt()).isEqualTo("explain this");
        assertThat(command.positional_prompt()).isNullOrEmpty();
    }

    @Test
    void parses_dash_p_long_form_print_flag() {
        JclaudeCommand command = parse_args("--print=summarize");

        assertThat(command.dash_p_prompt()).isEqualTo("summarize");
    }

    @Test
    void parses_json_output_format_flag() {
        // Mirrors `parses_bare_prompt_and_json_output_flag` —
        // crates/rusty-claude-cli/src/main.rs:9775.
        JclaudeCommand command = parse_args("--output-format=json", "hello");

        assertThat(command.output_format()).isEqualTo(OutputFormat.JSON);
        assertThat(command.positional_prompt()).containsExactly("hello");
    }

    @Test
    void defaults_output_format_to_text() {
        JclaudeCommand command = parse_args("hello");

        assertThat(command.output_format()).isEqualTo(OutputFormat.TEXT);
    }

    @Test
    void parses_compact_flag() {
        // Mirrors `parses_compact_flag_for_prompt_mode` —
        // crates/rusty-claude-cli/src/main.rs:9802.
        JclaudeCommand command = parse_args("--compact", "summarize", "this");

        assertThat(command.compact()).isTrue();
        assertThat(command.positional_prompt()).containsExactly("summarize", "this");
    }

    @Test
    void defaults_compact_flag_to_false() {
        // Mirrors `prompt_subcommand_defaults_compact_to_false` —
        // crates/rusty-claude-cli/src/main.rs:9833.
        JclaudeCommand command = parse_args("hello");

        assertThat(command.compact()).isFalse();
    }

    @Test
    void parses_dangerously_skip_permissions_flag() {
        // Mirrors `dangerously_skip_permissions_flag_*` —
        // crates/rusty-claude-cli/src/main.rs:9955 / :9976.
        JclaudeCommand command = parse_args("--dangerously-skip-permissions", "hello");

        assertThat(command.dangerously_skip_permissions()).isTrue();
    }

    @Test
    void defaults_dangerously_skip_permissions_to_false() {
        JclaudeCommand command = parse_args("hello");

        assertThat(command.dangerously_skip_permissions()).isFalse();
    }

    @Test
    void parses_permission_mode_flag() {
        // Mirrors `parses_permission_mode_flag` —
        // crates/rusty-claude-cli/src/main.rs:9939.
        JclaudeCommand command = parse_args("--permission-mode", "danger-full-access", "hello");

        assertThat(command.permission_mode_option()).isEqualTo(PermissionModeOption.DANGER_FULL_ACCESS);
    }

    @Test
    void defaults_permission_mode_to_read_only() {
        JclaudeCommand command = parse_args("hello");

        assertThat(command.permission_mode_option()).isEqualTo(PermissionModeOption.READ_ONLY);
    }

    @Test
    void parses_allowed_tools_csv() {
        // Mirrors `parses_allowed_tools_flags_with_aliases_and_lists` —
        // crates/rusty-claude-cli/src/main.rs:10006.
        JclaudeCommand command = parse_args("--allowedTools", "read_file,grep_search", "hello");

        assertThat(command.allowed_tools_csv()).isEqualTo("read_file,grep_search");
    }

    @Test
    void parses_model_flag() {
        // Mirrors `resolves_model_aliases_in_args` —
        // crates/rusty-claude-cli/src/main.rs:9850. The Java CLI keeps the
        // raw flag value; alias resolution happens later in WireRunner via
        // Providers.resolve_model_alias.
        JclaudeCommand command = parse_args("--model", "opus", "hello");

        assertThat(command.model()).isEqualTo("opus");
    }

    @Test
    void defaults_model_to_claude_sonnet_4_6() {
        JclaudeCommand command = parse_args("hello");

        assertThat(command.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void parses_resume_flag_with_path() {
        // Mirrors `parses_resume_flag_with_slash_command` —
        // crates/rusty-claude-cli/src/main.rs:11373. The Java CLI accepts
        // the file path; slash-command dispatch is Rust-only and absent here.
        JclaudeCommand command = parse_args("--resume", "/tmp/session.jsonl", "continue");

        assertThat(command.resume()).isEqualTo("/tmp/session.jsonl");
        assertThat(command.positional_prompt()).containsExactly("continue");
    }

    @Test
    void parses_max_tokens_flag() {
        JclaudeCommand command = parse_args("--max-tokens=2048", "hello");

        assertThat(command.max_tokens()).isEqualTo(2048L);
    }

    @Test
    void defaults_max_tokens_to_zero_meaning_use_model_default() {
        JclaudeCommand command = parse_args("hello");

        assertThat(command.max_tokens()).isEqualTo(0L);
    }

    @Test
    void rejects_unknown_options_with_helpful_guidance() {
        // Mirrors `rejects_unknown_options_with_helpful_guidance` —
        // crates/rusty-claude-cli/src/main.rs:11434. Picocli surfaces the
        // unknown option as a non-zero parse exit code; for the Java MVP we
        // assert the parser refuses the flag rather than the exact message
        // wording (which differs between picocli and clap).
        CommandLine cli = new CommandLine(new JclaudeCommand());
        int rc = cli.execute("--no-such-flag", "hello");

        assertThat(rc).isNotZero();
    }

    private static JclaudeCommand parse_args(String... args) {
        JclaudeCommand command = new JclaudeCommand();
        CommandLine line = new CommandLine(command);
        // parseArgs (vs execute) populates fields without invoking call().
        line.parseArgs(args);
        return command;
    }

    @SuppressWarnings("unused")
    private static List<String> as_list(String... args) {
        return List.of(args);
    }
}
