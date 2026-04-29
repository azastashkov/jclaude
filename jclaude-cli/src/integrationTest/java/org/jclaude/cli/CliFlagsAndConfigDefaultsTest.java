package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java port of {@code crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs}. Each Rust
 * test is reproduced by name; tests that rely on Rust-only subcommands ({@code status},
 * {@code version}, {@code doctor}, {@code config}, slash-command dispatcher, OMC plugin shim) are
 * marked {@link Disabled} with a reference to the Rust source line they came from. The covered
 * subset exercises picocli flag handling and the missing-prompt error path that the Java MVP CLI
 * actually implements today.
 */
final class CliFlagsAndConfigDefaultsTest {

    @Test
    void missing_prompt_produces_a_helpful_error_and_exit_code_2(@TempDir Path temp_dir) throws Exception {
        // The Java MVP equivalent of "running with no prompt does not silently
        // succeed". The Rust suite asserts the same exit shape via the
        // status/help subcommands, which the Java CLI does not own; the
        // missing-prompt path is the closest comparable contract.
        JclaudeBinary.Result result =
                JclaudeBinary.run(temp_dir, with_offline_anthropic_env(), null, "--model", "sonnet");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("missing prompt");
        assertThat(result.stderr()).contains("-p");
    }

    @Test
    void help_flag_prints_usage_and_exits_zero(@TempDir Path temp_dir) throws Exception {
        // Java MVP analogue of `local_subcommand_help_does_not_fall_through...`
        // — picocli's auto-generated help is the local-only fast path that the
        // Rust suite asserts on. Java has a single top-level help screen.
        JclaudeBinary.Result result = JclaudeBinary.run(temp_dir, JclaudeBinary.emptyEnv(), null, "--help");

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("Usage: jclaude");
        assertThat(result.stdout()).contains("--model");
        assertThat(result.stdout()).contains("--permission-mode");
    }

    @Test
    void version_flag_prints_version_and_exits_zero(@TempDir Path temp_dir) throws Exception {
        // Java MVP analogue of the Rust `version` subcommand contract; the
        // Java CLI exposes `--version` via picocli instead of a subcommand.
        JclaudeBinary.Result result = JclaudeBinary.run(temp_dir, JclaudeBinary.emptyEnv(), null, "--version");

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("jclaude");
    }

    @Test
    void model_alias_is_accepted_without_provider_call(@TempDir Path temp_dir) throws Exception {
        // The Rust harness validates `claude-sonnet-4-6` rendering through the
        // status subcommand; the Java MVP has no status subcommand, so we
        // assert the alias is at least accepted by the parser by combining it
        // with the missing-prompt error path. A successful turn would require
        // a live or mocked provider, which is covered by the parityTest suite.
        JclaudeBinary.Result result = JclaudeBinary.run(
                temp_dir, with_offline_anthropic_env(), null, "--model", "sonnet", "--permission-mode", "read-only");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("missing prompt");
    }

    @Test
    void status_command_applies_model_and_permission_mode_flags(@TempDir Path temp_dir) throws Exception {
        // Mirrors `status_command_applies_model_and_permission_mode_flags` —
        // crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs:11.
        // Java equivalent: status subcommand surfaces the parsed --model and
        // --permission-mode flags via both text and JSON output.
        JclaudeBinary.Result text = JclaudeBinary.run(
                temp_dir,
                with_offline_anthropic_env(),
                null,
                "status",
                "--model",
                "sonnet",
                "--permission-mode",
                "workspace-write");
        assertThat(text.exitCode())
                .as("status text run, stderr=%s", text.stderr())
                .isZero();
        assertThat(text.stdout()).contains("Model");
        assertThat(text.stdout()).contains("claude-sonnet-4-6"); // alias resolved
        assertThat(text.stdout()).contains("workspace-write");

        JclaudeBinary.Result json = JclaudeBinary.run(
                temp_dir,
                with_offline_anthropic_env(),
                null,
                "status",
                "--output-format",
                "json",
                "--model",
                "opus",
                "--permission-mode",
                "danger-full-access");
        assertThat(json.exitCode()).isZero();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(json.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("status");
        assertThat(parsed.path("model").asText()).isEqualTo("claude-opus-4-6");
        assertThat(parsed.path("permission_mode").asText()).isEqualTo("danger-full-access");
    }

    @Test
    void config_command_loads_defaults_from_standard_config_locations(@TempDir Path temp_dir) throws Exception {
        // Mirrors `config_command_loads_defaults_from_standard_config_locations` —
        // crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs:132. The
        // Java port honours JCLAUDE_CONFIG_HOME (instead of the Rust-only
        // CLAW_CONFIG_HOME) and only consults a single user-scope file (not the
        // layered stack — see file-level disabled tests for the layered cases).
        Path config_home = temp_dir.resolve("config-home");
        Files.createDirectories(config_home);
        Files.writeString(config_home.resolve("settings.json"), "{\n  \"model\": \"sonnet\"\n}\n");
        Map<String, String> env = with_offline_anthropic_env();
        env.put("JCLAUDE_CONFIG_HOME", config_home.toString());

        JclaudeBinary.Result list_run = JclaudeBinary.run(temp_dir, env, null, "config", "list");
        assertThat(list_run.exitCode())
                .as("config list exit, stderr=%s", list_run.stderr())
                .isZero();
        assertThat(list_run.stdout()).contains("model = sonnet");

        JclaudeBinary.Result get_run =
                JclaudeBinary.run(temp_dir, env, null, "config", "get", "model", "--output-format", "json");
        assertThat(get_run.exitCode()).isZero();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(get_run.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("config");
        assertThat(parsed.path("action").asText()).isEqualTo("get");
        assertThat(parsed.path("present").asBoolean()).isTrue();
        assertThat(parsed.path("value").asText()).isEqualTo("sonnet");
    }

    @Test
    void doctor_command_runs_as_a_local_shell_entrypoint(@TempDir Path temp_dir) throws Exception {
        // Mirrors `doctor_command_runs_as_a_local_shell_entrypoint` —
        // crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs:188. The
        // Java doctor reports OS, java version, anthropic-key presence (without
        // echoing values), and settings paths — exit 0 with no provider call.
        JclaudeBinary.Result run = JclaudeBinary.run(temp_dir, JclaudeBinary.emptyEnv(), null, "doctor");
        assertThat(run.exitCode()).as("doctor exit, stderr=%s", run.stderr()).isZero();
        assertThat(run.stdout()).contains("Doctor");
        assertThat(run.stdout()).contains("OS ");
        assertThat(run.stdout()).contains("Java ");
        assertThat(run.stdout()).contains("ANTHROPIC_API_KEY");
        // The doctor must never echo the API-key value, even when it's set.
        Map<String, String> env = with_offline_anthropic_env();
        env.put("ANTHROPIC_API_KEY", "DO-NOT-ECHO-secret-1234");
        JclaudeBinary.Result with_key = JclaudeBinary.run(temp_dir, env, null, "doctor");
        assertThat(with_key.exitCode()).isZero();
        assertThat(with_key.stdout()).doesNotContain("DO-NOT-ECHO-secret-1234");
        assertThat(with_key.stdout()).contains("present");
    }

    @Test
    void local_subcommand_help_does_not_fall_through_to_runtime_or_provider_calls(@TempDir Path temp_dir)
            throws Exception {
        // Mirrors `local_subcommand_help_does_not_fall_through_to_runtime_or_provider_calls` —
        // crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs:218. The
        // Java port has the same property: `<subcommand> --help` exits 0 with the
        // picocli usage block, never reaching the provider.
        for (String subcommand :
                new String[] {"doctor", "status", "config", "init", "sandbox", "agents", "skills", "mcp"}) {
            JclaudeBinary.Result run =
                    JclaudeBinary.run(temp_dir, with_offline_anthropic_env(), null, subcommand, "--help");
            assertThat(run.exitCode())
                    .as("subcommand %s --help exit", subcommand)
                    .isZero();
            assertThat(run.stdout())
                    .as("subcommand %s --help usage", subcommand)
                    .contains("Usage:");
        }
    }

    /**
     * Returns an env that points the Anthropic adapter at an unreachable port so any accidental
     * provider call fails fast rather than dialing the real API. The picocli error paths under
     * test never reach the network.
     */
    private static Map<String, String> with_offline_anthropic_env() {
        Map<String, String> env = new HashMap<>();
        env.put("ANTHROPIC_API_KEY", "test-cli-flags-offline");
        env.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:9");
        return env;
    }
}
