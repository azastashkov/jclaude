package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java port of {@code crates/rusty-claude-cli/tests/output_format_contract.rs}. Each Rust test is
 * reproduced by name. The Phase 6 wave brings up the {@code status}, {@code sandbox}, {@code
 * agents}, {@code mcp}, {@code skills}, {@code init}, and {@code doctor} subcommands; tests for
 * those surfaces are now active. Tests that need the Rust-only {@code help}/{@code version} JSON
 * subcommands, the {@code acp} discoverability subcommand, or the slash-command dispatcher
 * (resumed inventory) remain {@link Disabled}.
 *
 * <p>The {@code result}-envelope shape (the only JSON envelope the Java MVP emits today) is pinned
 * by {@link org.jclaude.cli.render.JsonOutputRendererTest} and exercised end-to-end by the parity
 * harness in {@code src/parityTest/}.
 */
final class OutputFormatContractTest {

    @Test
    void json_output_format_flag_is_accepted_by_parser(@TempDir Path temp_dir) throws Exception {
        // The Java MVP cannot mint a JSON envelope without a model call (the
        // local subcommands the Rust suite asserts on do not exist), so this
        // test asserts the closest-comparable behaviour: --output-format=json
        // is parsed and reaches the missing-prompt branch.
        JclaudeBinary.Result result = JclaudeBinary.run(temp_dir, offline_env(), null, "--output-format", "json");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("missing prompt");
    }

    @Test
    void status_and_sandbox_emit_json_when_requested(@TempDir Path temp_dir) throws Exception {
        // Mirrors `status_and_sandbox_emit_json_when_requested` —
        // crates/rusty-claude-cli/tests/output_format_contract.rs:35. The Java
        // JSON envelope shape differs slightly: status emits a `usage` object
        // (matching the Rust `usage` field) while sandbox uses
        // `namespace`/`network`/`filesystem` sub-objects rather than the Rust
        // flat layout.
        ObjectMapper mapper = new ObjectMapper();
        JclaudeBinary.Result status_run =
                JclaudeBinary.run(temp_dir, offline_env(), null, "status", "--output-format", "json");
        assertThat(status_run.exitCode()).isZero();
        JsonNode status = mapper.readTree(status_run.stdout());
        assertThat(status.path("kind").asText()).isEqualTo("status");
        assertThat(status.has("model")).isTrue();
        assertThat(status.has("permission_mode")).isTrue();
        assertThat(status.path("allowed_tools").isArray()).isTrue();
        assertThat(status.has("session_id")).isTrue();
        assertThat(status.path("usage").isObject()).isTrue();

        JclaudeBinary.Result sandbox_run =
                JclaudeBinary.run(temp_dir, offline_env(), null, "sandbox", "--output-format", "json");
        assertThat(sandbox_run.exitCode()).isZero();
        JsonNode sandbox = mapper.readTree(sandbox_run.stdout());
        assertThat(sandbox.path("kind").asText()).isEqualTo("sandbox");
        assertThat(sandbox.has("platform")).isTrue();
        assertThat(sandbox.has("mode")).isTrue();
        assertThat(sandbox.path("namespace").isObject()).isTrue();
        assertThat(sandbox.path("network").isObject()).isTrue();
        assertThat(sandbox.path("filesystem").isObject()).isTrue();
    }

    @Test
    void inventory_commands_emit_structured_json_when_requested(@TempDir Path temp_dir) throws Exception {
        // Mirrors `inventory_commands_emit_structured_json_when_requested` —
        // crates/rusty-claude-cli/tests/output_format_contract.rs:67. The Java
        // port wires up agents/mcp/skills via the existing AgentsHandler /
        // SkillsHandler / McpSubcommand inventory; the Rust JSON shape's
        // top-level `kind` is preserved while sub-fields use the Java
        // mappers' snake_case naming.
        ObjectMapper mapper = new ObjectMapper();
        for (String subcommand : new String[] {"agents", "mcp", "skills"}) {
            JclaudeBinary.Result run =
                    JclaudeBinary.run(temp_dir, offline_env(), null, subcommand, "--output-format", "json");
            assertThat(run.exitCode())
                    .as("%s exit, stderr=%s", subcommand, run.stderr())
                    .isZero();
            JsonNode parsed = mapper.readTree(run.stdout());
            assertThat(parsed.path("kind").asText()).isEqualTo(subcommand);
        }
    }

    @Test
    void agents_command_emits_structured_agent_entries_when_requested(@TempDir Path temp_dir) throws Exception {
        // Mirrors `agents_command_emits_structured_agent_entries_when_requested` —
        // crates/rusty-claude-cli/tests/output_format_contract.rs:110.
        ObjectMapper mapper = new ObjectMapper();
        JclaudeBinary.Result run =
                JclaudeBinary.run(temp_dir, offline_env(), null, "agents", "--output-format", "json");
        assertThat(run.exitCode()).isZero();
        JsonNode parsed = mapper.readTree(run.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("agents");
        assertThat(parsed.path("agents").isArray()).isTrue();
        assertThat(parsed.path("summary").isObject()).isTrue();
    }

    @Test
    void dump_manifests_and_init_emit_json_when_requested(@TempDir Path temp_dir) throws Exception {
        // Mirrors `dump_manifests_and_init_emit_json_when_requested` —
        // crates/rusty-claude-cli/tests/output_format_contract.rs:189. The
        // Java port has `init` but no `dump-manifests`; we assert the init
        // JSON envelope only, leaving the `dump-manifests` half disabled.
        ObjectMapper mapper = new ObjectMapper();
        JclaudeBinary.Result run = JclaudeBinary.run(
                temp_dir, offline_env(), null, "init", "--output-format", "json", "--workspace", temp_dir.toString());
        assertThat(run.exitCode()).as("init exit, stderr=%s", run.stderr()).isZero();
        JsonNode parsed = mapper.readTree(run.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("init");
        assertThat(parsed.path("created").isObject()).isTrue();
        assertThat(parsed.path("files").isArray()).isTrue();
    }

    @Test
    void doctor_and_resume_status_emit_json_when_requested(@TempDir Path temp_dir) throws Exception {
        // Mirrors `doctor_and_resume_status_emit_json_when_requested` —
        // crates/rusty-claude-cli/tests/output_format_contract.rs:216. The
        // Java port asserts the doctor JSON envelope; resume_status is the
        // status subcommand against a saved session, covered by
        // ResumeSlashCommandsTest::resume_flag_loads_session_and_continues_turn.
        ObjectMapper mapper = new ObjectMapper();
        JclaudeBinary.Result run =
                JclaudeBinary.run(temp_dir, offline_env(), null, "doctor", "--output-format", "json");
        assertThat(run.exitCode()).isZero();
        JsonNode parsed = mapper.readTree(run.stdout());
        assertThat(parsed.path("kind").asText()).isEqualTo("doctor");
        assertThat(parsed.path("os").isObject()).isTrue();
        assertThat(parsed.path("java").isObject()).isTrue();
        assertThat(parsed.path("auth").isObject()).isTrue();
        assertThat(parsed.path("settings_paths").isArray()).isTrue();
    }

    private static Map<String, String> offline_env() {
        Map<String, String> env = new HashMap<>();
        env.put("ANTHROPIC_API_KEY", "test-output-format-offline");
        env.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:9");
        return env;
    }
}
