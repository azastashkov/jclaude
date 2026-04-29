package org.jclaude.cli.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaBashTest extends AbstractOllamaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bash_tool_executes_simple_command(@TempDir Path workspace) throws Exception {
        JclaudeProcess.Result result = JclaudeProcess.run(
                workspace,
                JclaudeProcess.emptyEnv(),
                null,
                "--model",
                MODEL,
                "--permission-mode",
                "danger-full-access",
                "--allowedTools",
                "bash",
                "--output-format",
                "json",
                "-p",
                "Run echo banana");

        assertThat(result.exitCode())
                .as("exit code (stderr=%s, stdout=%s)", result.stderr(), result.stdout())
                .isEqualTo(0);

        JsonNode root = mapper.readTree(result.stdout());
        JsonNode tool_uses = root.path("tool_uses");
        boolean used_bash = false;
        for (JsonNode use : tool_uses) {
            if ("bash".equals(use.path("name").asText())) {
                used_bash = true;
                break;
            }
        }
        assertThat(used_bash).as("expected a bash tool_use").isTrue();

        JsonNode tool_results = root.path("tool_results");
        boolean stdout_has_banana = false;
        for (JsonNode tool_result : tool_results) {
            if (!"bash".equals(tool_result.path("tool_name").asText())) {
                continue;
            }
            String output_raw = tool_result.path("output").asText();
            // The bash tool serializes the BashCommandOutput record as JSON;
            // its `stdout` field carries the captured output.
            try {
                JsonNode parsed = mapper.readTree(output_raw);
                String captured = parsed.path("stdout").asText("");
                if (captured.toLowerCase().contains("banana")) {
                    stdout_has_banana = true;
                    break;
                }
            } catch (Exception ignored) {
                if (output_raw.toLowerCase().contains("banana")) {
                    stdout_has_banana = true;
                    break;
                }
            }
        }
        assertThat(stdout_has_banana)
                .as("expected bash stdout to contain 'banana'")
                .isTrue();
    }
}
