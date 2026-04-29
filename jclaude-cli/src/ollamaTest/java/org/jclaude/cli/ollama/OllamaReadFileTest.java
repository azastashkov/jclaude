package org.jclaude.cli.ollama;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaReadFileTest extends AbstractOllamaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void read_file_tool_returns_file_content(@TempDir Path workspace) throws Exception {
        Path data = workspace.resolve("data.txt");
        Files.writeString(data, "umbrella alpha\n", UTF_8);

        JclaudeProcess.Result result = JclaudeProcess.run(
                workspace,
                JclaudeProcess.emptyEnv(),
                null,
                "--model",
                MODEL,
                "--permission-mode",
                "read-only",
                "--allowedTools",
                "read_file",
                "--output-format",
                "json",
                "-p",
                "Read data.txt and tell me the word on line 1");

        assertThat(result.exitCode())
                .as("exit code (stderr=%s, stdout=%s)", result.stderr(), result.stdout())
                .isEqualTo(0);

        JsonNode root = mapper.readTree(result.stdout());
        JsonNode tool_uses = root.path("tool_uses");
        boolean used_read_file = false;
        for (JsonNode use : tool_uses) {
            if ("read_file".equals(use.path("name").asText())) {
                used_read_file = true;
                break;
            }
        }
        assertThat(used_read_file).as("expected a read_file tool_use").isTrue();
        assertThat(root.path("message").asText().toLowerCase()).contains("umbrella");
    }
}
