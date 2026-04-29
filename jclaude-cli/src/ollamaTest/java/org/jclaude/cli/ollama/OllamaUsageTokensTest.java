package org.jclaude.cli.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaUsageTokensTest extends AbstractOllamaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usage_tokens_reported_in_json_output(@TempDir Path workspace) throws Exception {
        JclaudeProcess.Result result = JclaudeProcess.run(
                workspace, JclaudeProcess.emptyEnv(), null, "--model", MODEL, "--output-format", "json", "-p", "hi");

        assertThat(result.exitCode())
                .as("exit code (stderr=%s, stdout=%s)", result.stderr(), result.stdout())
                .isEqualTo(0);

        JsonNode root = mapper.readTree(result.stdout());
        JsonNode usage = root.path("usage");
        assertThat(usage.path("input_tokens").asLong()).as("input_tokens").isGreaterThanOrEqualTo(1);
        assertThat(usage.path("output_tokens").asLong()).as("output_tokens").isGreaterThanOrEqualTo(1);
    }
}
