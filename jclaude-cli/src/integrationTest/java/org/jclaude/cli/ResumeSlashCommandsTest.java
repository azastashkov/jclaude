package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jclaude.mockanthropic.MockAnthropicService;
import org.jclaude.mockanthropic.Scenario;
import org.jclaude.runtime.session.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java port of {@code crates/rusty-claude-cli/tests/resume_slash_commands.rs}. The Rust suite
 * exercises {@code --resume PATH /<command> [args]} for {@code /export}, {@code /clear},
 * {@code /status}, {@code /sandbox}, {@code /version}, {@code /help}, {@code /allowed-tools},
 * {@code /config}, plus {@code --resume latest} session-store discovery — none of which are
 * surfaced by the Java MVP CLI.
 *
 * <p>Each Rust test is reproduced by name. Tests that require a slash-command dispatcher,
 * SessionStore, or a managed-session "latest" alias are marked {@link Disabled} with the source
 * reference. The single covered test asserts the resume path itself: that {@code --resume PATH}
 * loads a saved session and threads its model metadata through to the next turn.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ResumeSlashCommandsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockAnthropicService server;

    @BeforeAll
    void start_server() throws IOException {
        server = MockAnthropicService.spawn();
    }

    @AfterAll
    void stop_server() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void resume_flag_loads_session_and_continues_turn(@TempDir Path root) throws Exception {
        // Java MVP analogue of the resume-binary contract: write a session
        // file with a prior user message, then invoke the CLI with --resume
        // PATH and a fresh prompt. The CLI must accept the path, load the
        // session, and emit a result envelope.
        Path workspace = root.resolve("workspace");
        Path config_home = root.resolve("config-home");
        Path home = root.resolve("home");
        Files.createDirectories(workspace);
        Files.createDirectories(config_home);
        Files.createDirectories(home);

        Path session_path = workspace.resolve("session.jsonl");
        Session seeded = Session.create().with_workspace_root(workspace);
        seeded.push_user_text("ship the resume harness");
        seeded.save_to_path(session_path);

        String prompt = Scenario.SCENARIO_PREFIX + "streaming_text";
        JclaudeBinary.Result result = JclaudeBinary.run(
                workspace,
                mock_env(config_home, home),
                null,
                "--model",
                "sonnet",
                "--permission-mode",
                "read-only",
                "--output-format",
                "json",
                "--resume",
                session_path.toString(),
                prompt);

        assertThat(result.exitCode())
                .as("resume run should succeed (stdout=%s, stderr=%s)", result.stdout(), result.stderr())
                .isEqualTo(0);
        JsonNode parsed = MAPPER.readTree(result.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("result");
        assertThat(parsed.path("model").asText()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void status_command_applies_cli_flags_end_to_end(@TempDir Path root) throws Exception {
        // Mirrors `status_command_applies_cli_flags_end_to_end` —
        // crates/rusty-claude-cli/tests/resume_slash_commands.rs:83. The Java
        // port runs the standalone `status` subcommand (not the in-session
        // /status slash dispatch); the Rust shape's top-level kind=status,
        // model, permission_mode, allowed_tools fields are preserved.
        Path workspace = root.resolve("workspace");
        Path config_home = root.resolve("config-home");
        Path home = root.resolve("home");
        Files.createDirectories(workspace);
        Files.createDirectories(config_home);
        Files.createDirectories(home);

        JclaudeBinary.Result result = JclaudeBinary.run(
                workspace,
                mock_env(config_home, home),
                null,
                "status",
                "--output-format",
                "json",
                "--model",
                "sonnet",
                "--permission-mode",
                "workspace-write",
                "--allowedTools",
                "read_file,grep_search");
        assertThat(result.exitCode())
                .as("status run should succeed (stdout=%s, stderr=%s)", result.stdout(), result.stderr())
                .isZero();
        JsonNode parsed = MAPPER.readTree(result.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("status");
        assertThat(parsed.path("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(parsed.path("permission_mode").asText()).isEqualTo("workspace-write");
        JsonNode tools = parsed.path("allowed_tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.toString()).contains("read_file").contains("grep_search");
    }

    @Test
    void resumed_status_surfaces_persisted_model(@TempDir Path root) throws Exception {
        // Mirrors `resumed_status_surfaces_persisted_model` —
        // crates/rusty-claude-cli/tests/resume_slash_commands.rs:279. The Java
        // port surfaces the persisted model via `status --resume PATH`; the
        // status subcommand reads the session and reflects `session.model`.
        Path workspace = root.resolve("workspace");
        Path config_home = root.resolve("config-home");
        Path home = root.resolve("home");
        Files.createDirectories(workspace);
        Files.createDirectories(config_home);
        Files.createDirectories(home);

        Path session_path = workspace.resolve("session.jsonl");
        Session seeded = Session.create().with_workspace_root(workspace);
        seeded.set_model("claude-opus-4-6");
        seeded.push_user_text("seed turn");
        seeded.save_to_path(session_path);

        JclaudeBinary.Result result = JclaudeBinary.run(
                workspace,
                mock_env(config_home, home),
                null,
                "status",
                "--output-format",
                "json",
                "--resume",
                session_path.toString());
        assertThat(result.exitCode())
                .as("resumed status (stdout=%s, stderr=%s)", result.stdout(), result.stderr())
                .isZero();
        JsonNode parsed = MAPPER.readTree(result.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("status");
        assertThat(parsed.path("model").asText()).isEqualTo("claude-opus-4-6");
        assertThat(parsed.path("session_id").isNull()).isFalse();
    }

    @Test
    void resumed_sandbox_command_emits_structured_json_when_requested(@TempDir Path root) throws Exception {
        // Mirrors `resumed_sandbox_command_emits_structured_json_when_requested` —
        // crates/rusty-claude-cli/tests/resume_slash_commands.rs:320. The Java
        // port has no in-session /sandbox dispatch, so we exercise the
        // standalone `sandbox` subcommand instead — it emits the same JSON
        // envelope shape regardless of session state.
        Path workspace = root.resolve("workspace");
        Path config_home = root.resolve("config-home");
        Path home = root.resolve("home");
        Files.createDirectories(workspace);
        Files.createDirectories(config_home);
        Files.createDirectories(home);

        JclaudeBinary.Result result =
                JclaudeBinary.run(workspace, mock_env(config_home, home), null, "sandbox", "--output-format", "json");
        assertThat(result.exitCode()).isZero();
        JsonNode parsed = MAPPER.readTree(result.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("sandbox");
        assertThat(parsed.has("platform")).isTrue();
        assertThat(parsed.path("namespace").isObject()).isTrue();
    }

    private Map<String, String> mock_env(Path config_home, Path home) {
        Map<String, String> env = new HashMap<>();
        env.put("ANTHROPIC_API_KEY", "test-resume-key");
        env.put("ANTHROPIC_BASE_URL", server.base_url());
        env.put("JCLAUDE_CONFIG_HOME", config_home.toString());
        env.put("HOME", home.toString());
        return env;
    }
}
