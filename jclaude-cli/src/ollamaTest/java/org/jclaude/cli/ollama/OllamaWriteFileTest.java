package org.jclaude.cli.ollama;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaWriteFileTest extends AbstractOllamaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void write_file_tool_creates_requested_file(@TempDir Path workspace) throws Exception {
        JclaudeProcess.Result result = JclaudeProcess.run(
                workspace,
                JclaudeProcess.emptyEnv(),
                null,
                "--model",
                MODEL,
                "--permission-mode",
                "workspace-write",
                "--allowedTools",
                "write_file",
                "--output-format",
                "json",
                "-p",
                "Write hello.txt with content: hi");

        assertThat(result.exitCode())
                .as("exit code (stderr=%s, stdout=%s)", result.stderr(), result.stdout())
                .isEqualTo(0);

        Path created = workspace.resolve("hello.txt");
        assertThat(created).as("workspace/hello.txt should exist").exists();
        assertThat(Files.readString(created, UTF_8).toLowerCase()).contains("hi");

        JsonNode root = mapper.readTree(result.stdout());
        JsonNode tool_uses = root.path("tool_uses");
        assertThat(tool_uses.isArray()).isTrue();
        boolean used_write_file = false;
        for (JsonNode use : tool_uses) {
            if ("write_file".equals(use.path("name").asText())) {
                used_write_file = true;
                break;
            }
        }
        assertThat(used_write_file)
                .as("expected at least one write_file tool_use")
                .isTrue();
    }
}
