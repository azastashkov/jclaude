package org.jclaude.cli.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractOllamaTest {

    public static final String BASE_URL = "http://localhost:11434/v1";
    public static final String OLLAMA_BASE = "http://localhost:11434";
    public static final String MODEL = "openai/qwen3-coder:30b-a3b-fp16";
    public static final String OLLAMA_MODEL_TAG = "qwen3-coder:30b-a3b-fp16";
    public static final Duration TIMEOUT = Duration.ofMinutes(2);

    @BeforeAll
    public void preflight() throws Exception {
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> tags;
        try {
            tags = http.send(
                    HttpRequest.newBuilder(URI.create(OLLAMA_BASE + "/api/tags"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            Assumptions.abort("ollama not reachable at " + OLLAMA_BASE + " — skipping ollama suite");
            return;
        }
        assertThat(tags.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(tags.body());
        boolean hasModel = false;
        for (JsonNode m : root.path("models")) {
            if (m.path("name").asText().contains(OLLAMA_MODEL_TAG)) {
                hasModel = true;
                break;
            }
        }
        Assumptions.assumeTrue(
                hasModel, "model " + OLLAMA_MODEL_TAG + " not present — run `ollama pull " + OLLAMA_MODEL_TAG + "`");
    }
}
