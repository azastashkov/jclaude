package org.jclaude.cli.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.jclaude.api.json.JclaudeMappers;

/** Canonical list of the 12 mock-parity scenarios with prepare + assertion lambdas. */
public final class ScenarioCases {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private ScenarioCases() {}

    public static List<ScenarioCase> all() {
        return List.of(
                new ScenarioCase(
                        "streaming_text", "read-only", null, null, noop(), ScenarioCases::assert_streaming_text),
                new ScenarioCase(
                        "read_file_roundtrip",
                        "read-only",
                        "read_file",
                        null,
                        prepare("read_file_roundtrip"),
                        ScenarioCases::assert_read_file_roundtrip),
                new ScenarioCase(
                        "grep_chunk_assembly",
                        "read-only",
                        "grep_search",
                        null,
                        prepare("grep_chunk_assembly"),
                        ScenarioCases::assert_grep_chunk_assembly),
                new ScenarioCase(
                        "write_file_allowed",
                        "workspace-write",
                        "write_file",
                        null,
                        noop(),
                        ScenarioCases::assert_write_file_allowed),
                new ScenarioCase(
                        "write_file_denied",
                        "read-only",
                        "write_file",
                        null,
                        noop(),
                        ScenarioCases::assert_write_file_denied),
                new ScenarioCase(
                        "multi_tool_turn_roundtrip",
                        "read-only",
                        "read_file,grep_search",
                        null,
                        prepare("multi_tool_turn_roundtrip"),
                        ScenarioCases::assert_multi_tool_turn_roundtrip),
                new ScenarioCase(
                        "bash_stdout_roundtrip",
                        "danger-full-access",
                        "bash",
                        null,
                        noop(),
                        ScenarioCases::assert_bash_stdout_roundtrip),
                new ScenarioCase(
                        "bash_permission_prompt_approved",
                        "workspace-write",
                        "bash",
                        "y\n",
                        noop(),
                        ScenarioCases::assert_bash_permission_prompt_approved),
                new ScenarioCase(
                        "bash_permission_prompt_denied",
                        "workspace-write",
                        "bash",
                        "n\n",
                        noop(),
                        ScenarioCases::assert_bash_permission_prompt_denied),
                new ScenarioCase(
                        "plugin_tool_roundtrip",
                        "workspace-write",
                        null,
                        null,
                        prepare("plugin_tool_roundtrip"),
                        ScenarioCases::assert_plugin_tool_roundtrip),
                new ScenarioCase(
                        "auto_compact_triggered",
                        "read-only",
                        null,
                        null,
                        noop(),
                        ScenarioCases::assert_auto_compact_triggered),
                new ScenarioCase(
                        "token_cost_reporting",
                        "read-only",
                        null,
                        null,
                        noop(),
                        ScenarioCases::assert_token_cost_reporting));
    }

    public static List<String> canonical_names() {
        return all().stream().map(ScenarioCase::name).toList();
    }

    // -------------------------------------------------------------------- prepare

    private static Consumer<HarnessWorkspace> noop() {
        return ws -> {};
    }

    private static Consumer<HarnessWorkspace> prepare(String scenario) {
        return ws -> {
            try {
                ws.prepare(scenario);
            } catch (IOException error) {
                throw new IllegalStateException("failed to prepare fixtures for " + scenario, error);
            }
        };
    }

    // ------------------------------------------------------------------ assertions

    private static void assert_streaming_text(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(text_field(run, "message")).isEqualTo("Mock streaming says hello from the parity harness.");
        assertThat(int_field(run, "iterations")).isEqualTo(1);
        assertThat(run.response().path("tool_uses").isArray()).isTrue();
        assertThat(run.response().path("tool_uses")).isEmpty();
        assertThat(run.response().path("tool_results")).isEmpty();
    }

    private static void assert_read_file_roundtrip(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        JsonNode tool_use = run.response().path("tool_uses").path(0);
        assertThat(tool_use.path("name").asText()).isEqualTo("read_file");
        // Java's renderer parses the input string into a JSON object; assert the parsed shape.
        assertThat(tool_use.path("input").path("path").asText()).isEqualTo("fixture.txt");
        assertThat(text_field(run, "message")).contains("alpha parity line");
        String output =
                run.response().path("tool_results").path(0).path("output").asText();
        assertThat(output).contains(ws.workspace().resolve("fixture.txt").toString());
        assertThat(output).contains("alpha parity line");
    }

    private static void assert_grep_chunk_assembly(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        JsonNode tool_use = run.response().path("tool_uses").path(0);
        assertThat(tool_use.path("name").asText()).isEqualTo("grep_search");
        JsonNode input = tool_use.path("input");
        assertThat(input.path("pattern").asText()).isEqualTo("parity");
        assertThat(input.path("path").asText()).isEqualTo("fixture.txt");
        assertThat(input.path("output_mode").asText()).isEqualTo("count");
        assertThat(text_field(run, "message")).contains("2 occurrences");
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isFalse();
    }

    private static void assert_write_file_allowed(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        assertThat(run.response().path("tool_uses").path(0).path("name").asText())
                .isEqualTo("write_file");
        assertThat(text_field(run, "message")).contains("generated/output.txt");
        Path generated = ws.workspace().resolve("generated").resolve("output.txt");
        try {
            String contents = Files.readString(generated, StandardCharsets.UTF_8);
            assertThat(contents).isEqualTo("created by mock service\n");
        } catch (IOException error) {
            throw new AssertionError("expected generated file at " + generated, error);
        }
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isFalse();
    }

    private static void assert_write_file_denied(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        assertThat(run.response().path("tool_uses").path(0).path("name").asText())
                .isEqualTo("write_file");
        String tool_output =
                run.response().path("tool_results").path(0).path("output").asText();
        assertThat(tool_output).contains("requires workspace-write permission");
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isTrue();
        assertThat(text_field(run, "message")).contains("denied as expected");
        assertThat(Files.exists(ws.workspace().resolve("generated").resolve("denied.txt")))
                .isFalse();
    }

    private static void assert_multi_tool_turn_roundtrip(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        JsonNode tool_uses = run.response().path("tool_uses");
        assertThat(tool_uses.size()).isEqualTo(2);
        assertThat(tool_uses.path(0).path("name").asText()).isEqualTo("read_file");
        assertThat(tool_uses.path(1).path("name").asText()).isEqualTo("grep_search");
        JsonNode tool_results = run.response().path("tool_results");
        assertThat(tool_results.size()).isEqualTo(2);
        String message = text_field(run, "message");
        assertThat(message).contains("alpha parity line");
        assertThat(message).contains("2 occurrences");
    }

    private static void assert_bash_stdout_roundtrip(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        assertThat(run.response().path("tool_uses").path(0).path("name").asText())
                .isEqualTo("bash");
        String tool_output =
                run.response().path("tool_results").path(0).path("output").asText();
        JsonNode parsed = parse_json(tool_output);
        assertThat(parsed.path("stdout").asText()).isEqualTo("alpha from bash");
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isFalse();
        assertThat(text_field(run, "message")).contains("alpha from bash");
    }

    private static void assert_bash_permission_prompt_approved(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(run.stdout()).contains("Permission approval required");
        assertThat(run.stdout()).contains("Approve this tool call? [y/N]:");
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isFalse();
        String tool_output =
                run.response().path("tool_results").path(0).path("output").asText();
        JsonNode parsed = parse_json(tool_output);
        assertThat(parsed.path("stdout").asText()).isEqualTo("approved via prompt");
        assertThat(text_field(run, "message")).contains("approved and executed");
    }

    private static void assert_bash_permission_prompt_denied(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(run.stdout()).contains("Permission approval required");
        assertThat(run.stdout()).contains("Approve this tool call? [y/N]:");
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        String tool_output =
                run.response().path("tool_results").path(0).path("output").asText();
        assertThat(tool_output).contains("denied by user approval prompt");
        assertThat(run.response().path("tool_results").path(0).path("is_error").asBoolean())
                .isTrue();
        assertThat(text_field(run, "message")).contains("denied as expected");
    }

    private static void assert_plugin_tool_roundtrip(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(2);
        assertThat(run.response().path("tool_uses").path(0).path("name").asText())
                .isEqualTo("plugin_echo");
        String tool_output =
                run.response().path("tool_results").path(0).path("output").asText();
        JsonNode parsed = parse_json(tool_output);
        assertThat(parsed.path("plugin").asText()).isEqualTo("parity-plugin@external");
        assertThat(parsed.path("tool").asText()).isEqualTo("plugin_echo");
        assertThat(parsed.path("input").path("message").asText()).isEqualTo("hello from plugin parity");
        assertThat(text_field(run, "message")).contains("hello from plugin parity");
    }

    private static void assert_auto_compact_triggered(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(1);
        assertThat(run.response().path("tool_uses")).isEmpty();
        assertThat(text_field(run, "message")).contains("auto compact parity complete.");
        assertThat(run.response().has("auto_compaction"))
                .as("auto_compaction key must be present in JSON output")
                .isTrue();
        long input_tokens = run.response().path("usage").path("input_tokens").asLong();
        assertThat(input_tokens).isGreaterThanOrEqualTo(50_000);
    }

    private static void assert_token_cost_reporting(HarnessWorkspace ws, ScenarioRun run) {
        assertThat(int_field(run, "iterations")).isEqualTo(1);
        assertThat(text_field(run, "message")).contains("token cost reporting parity complete.");
        JsonNode usage = run.response().path("usage");
        assertThat(usage.path("input_tokens").asLong()).isGreaterThan(0);
        assertThat(usage.path("output_tokens").asLong()).isGreaterThan(0);
        assertThat(run.response().path("estimated_cost").asText()).startsWith("$");
    }

    // ---------------------------------------------------------------------- helpers

    private static String text_field(ScenarioRun run, String name) {
        return run.response().path(name).asText();
    }

    private static int int_field(ScenarioRun run, String name) {
        return run.response().path(name).asInt();
    }

    private static JsonNode parse_json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException error) {
            throw new AssertionError("expected tool output to be valid JSON: " + text, error);
        }
    }
}
