package org.jclaude.cli.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaStreamingTest extends AbstractOllamaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streaming_emits_text_response(@TempDir Path workspace) throws Exception {
        JclaudeProcess.Result result = JclaudeProcess.run(
                workspace,
                JclaudeProcess.emptyEnv(),
                null,
                "--model",
                MODEL,
                "--output-format",
                "json",
                "-p",
                "say the word ready");

        assertThat(result.exitCode())
                .as("exit code (stderr=%s)", result.stderr())
                .isEqualTo(0);
        JsonNode root = mapper.readTree(result.stdout());
        assertThat(root.path("kind").asText()).isEqualTo("result");
        assertThat(root.path("message").asText().toLowerCase()).contains("ready");
    }
}
