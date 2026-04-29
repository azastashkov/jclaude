package org.jclaude.api.providers.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.providers.CapturingHttpServer;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.MessageResponse;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.ToolChoice;
import org.jclaude.api.types.ToolDefinition;
import org.jclaude.api.types.ToolResultContentBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link AnthropicClient}. Each test stages one or more
 * canned HTTP responses on a local {@link CapturingHttpServer} and asserts the
 * client's request shape matches the Rust client's wire protocol.
 *
 * <p>Mirrors the test set in {@code crates/api/tests/client_integration.rs}.
 */
class AnthropicClientIntegrationTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private CapturingHttpServer server;

    @BeforeEach
    void start_server() throws IOException {
        server = new CapturingHttpServer();
    }

    @AfterEach
    void stop_server() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void send_message_posts_json_and_parses_response() throws IOException {
        String body = "{"
                + "\"id\":\"msg_test\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello from Claude\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\","
                + "\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":4},"
                + "\"request_id\":\"req_body_123\""
                + "}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_auth_token("proxy-token")
                .with_base_url(server.base_url()));
        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.id()).isEqualTo("msg_test");
        assertThat(response.total_tokens()).isEqualTo(16L);
        assertThat(response.request_id()).isEqualTo("req_body_123");
        assertThat(response.usage().cache_creation_input_tokens()).isZero();
        assertThat(response.usage().cache_read_input_tokens()).isZero();
        assertThat(response.content()).containsExactly(new OutputContentBlock.Text("Hello from Claude"));

        List<CapturingHttpServer.CapturedRequest> captured = server.captured();
        assertThat(captured).hasSize(1);
        CapturingHttpServer.CapturedRequest request = captured.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/messages");
        assertThat(request.headers()).containsEntry("x-api-key", "test-key");
        assertThat(request.headers()).containsEntry("authorization", "Bearer proxy-token");
        assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
        assertThat(request.headers()).containsEntry("user-agent", "jclaude/0.1.0");
        assertThat(request.headers().get("anthropic-beta"))
                .isEqualTo("claude-code-20250219,prompt-caching-scope-2026-01-05");

        JsonNode parsed_body = MAPPER.readTree(request.body());
        assertThat(parsed_body.get("model").asText()).isEqualTo("claude-3-7-sonnet-latest");
        assertThat(parsed_body.has("stream")).isTrue();
        assertThat(parsed_body.get("stream").asBoolean()).isFalse();
        assertThat(parsed_body.get("tools").get(0).get("name").asText()).isEqualTo("get_weather");
        assertThat(parsed_body.get("tool_choice").get("type").asText()).isEqualTo("auto");
        assertThat(parsed_body.has("betas")).isFalse();
    }

    @Test
    void send_message_uses_x_api_key_header_only_when_no_bearer_token() {
        String body = "{\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"only api key\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("only-key").with_base_url(server.base_url()));
        client.send_message(sample_request(false));

        CapturingHttpServer.CapturedRequest captured = server.captured().get(0);
        assertThat(captured.headers().get("x-api-key")).isEqualTo("only-key");
        assertThat(captured.headers().get("authorization")).isNull();
    }

    @Test
    void send_message_uses_bearer_only_when_no_api_key() {
        String body = "{\"id\":\"msg_b\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"only bearer\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_auth_token("oauth-only").with_base_url(server.base_url()));
        client.send_message(sample_request(false));

        CapturingHttpServer.CapturedRequest captured = server.captured().get(0);
        assertThat(captured.headers().get("authorization")).isEqualTo("Bearer oauth-only");
        assertThat(captured.headers().get("x-api-key")).isNull();
    }

    @Test
    void send_message_parses_prompt_cache_token_usage_from_response() {
        String body = "{"
                + "\"id\":\"msg_cache_tokens\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Cache tokens\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\","
                + "\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"cache_creation_input_tokens\":321,"
                + "\"cache_read_input_tokens\":654,\"output_tokens\":4}"
                + "}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("test-key").with_base_url(server.base_url()));
        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.usage().input_tokens()).isEqualTo(12);
        assertThat(response.usage().cache_creation_input_tokens()).isEqualTo(321);
        assertThat(response.usage().cache_read_input_tokens()).isEqualTo(654);
        assertThat(response.usage().output_tokens()).isEqualTo(4);
    }

    @Test
    void given_empty_usage_object_when_send_message_parses_response_then_usage_defaults_to_zero() {
        String body = "{"
                + "\"id\":\"msg_empty_usage\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello from Claude\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\","
                + "\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,"
                + "\"usage\":{}"
                + "}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("test-key").with_base_url(server.base_url()));
        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.id()).isEqualTo("msg_empty_usage");
        assertThat(response.total_tokens()).isZero();
        assertThat(response.usage().input_tokens()).isZero();
        assertThat(response.usage().cache_creation_input_tokens()).isZero();
        assertThat(response.usage().cache_read_input_tokens()).isZero();
        assertThat(response.usage().output_tokens()).isZero();
    }

    @Test
    void stream_message_parses_sse_events_with_tool_use() {
        String sse = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_stream\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":8,\"cache_creation_input_tokens\":13,\"cache_read_input_tokens\":21,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_123\",\"name\":\"get_weather\",\"input\":{}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":8,\"cache_creation_input_tokens\":34,\"cache_read_input_tokens\":55,\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(CapturingHttpServer.StagedResponse.sse(sse, Map.of("request-id", "req_stream_456")));

        PromptCache cache = new PromptCache("stream-session");
        AnthropicClient client = new AnthropicClient(
                AnthropicConfig.of_api_key("test-key")
                        .with_auth_token("proxy-token")
                        .with_base_url(server.base_url()),
                cache);

        try (AnthropicClient.StreamingResponse stream = client.stream_message(sample_request(false))) {
            assertThat(stream.request_id()).hasValue("req_stream_456");

            List<StreamEvent> events = new ArrayList<>();
            stream.iterator().forEachRemaining(events::add);

            assertThat(events).hasSize(6);
            assertThat(events.get(0)).isInstanceOf(StreamEvent.MessageStart.class);
            StreamEvent.ContentBlockStart cb_start = (StreamEvent.ContentBlockStart) events.get(1);
            assertThat(cb_start.content_block()).isInstanceOf(OutputContentBlock.ToolUse.class);
            StreamEvent.ContentBlockDelta cb_delta = (StreamEvent.ContentBlockDelta) events.get(2);
            assertThat(cb_delta.delta()).isInstanceOf(BlockDelta.InputJsonDelta.class);
            assertThat(events.get(3)).isInstanceOf(StreamEvent.ContentBlockStop.class);
            assertThat(events.get(4)).isInstanceOf(StreamEvent.MessageDelta.class);
            assertThat(events.get(5)).isInstanceOf(StreamEvent.MessageStop.class);

            OutputContentBlock.ToolUse use = (OutputContentBlock.ToolUse) cb_start.content_block();
            assertThat(use.name()).isEqualTo("get_weather");
        }

        CapturingHttpServer.CapturedRequest captured = server.captured().get(0);
        assertThat(captured.body()).contains("\"stream\":true");

        PromptCache.PromptCacheStats stats = cache.stats();
        // Two usage observations are recorded: one on MessageStart, one on MessageDelta.
        assertThat(stats.tracked_requests()).isEqualTo(2);
        assertThat(stats.last_cache_creation_input_tokens()).isEqualTo(34);
        assertThat(stats.last_cache_read_input_tokens()).isEqualTo(55);
    }

    @Test
    void retries_retryable_failures_before_succeeding() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                200,
                "{\"id\":\"msg_retry\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Recovered\"}],"
                        + "\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}"));

        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_base_url(server.base_url())
                .with_retry_policy(2, Duration.ofMillis(1), Duration.ofMillis(2)));

        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.total_tokens()).isEqualTo(5L);
        assertThat(server.captured()).hasSize(2);
    }

    @Test
    void surfaces_retry_exhaustion_for_persistent_retryable_errors() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"still busy\"}}"));

        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_base_url(server.base_url())
                .with_retry_policy(1, Duration.ofMillis(1), Duration.ofMillis(2)));

        assertThatThrownBy(() -> client.send_message(sample_request(false)))
                .isInstanceOf(AnthropicException.class)
                .satisfies(error -> {
                    AnthropicException ex = (AnthropicException) error;
                    assertThat(ex.kind()).isEqualTo(AnthropicException.Kind.RETRIES_EXHAUSTED);
                    assertThat(ex.status()).hasValue(503);
                    assertThat(ex.error_type()).hasValue("overloaded_error");
                });
        assertThat(server.captured()).hasSize(2);
    }

    @Test
    void retries_multiple_retryable_failures_with_exponential_backoff_and_jitter() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"again\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"still busy\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                200,
                "{\"id\":\"msg_exp_retry\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Recovered after 5\"}],"
                        + "\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}"));

        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_base_url(server.base_url())
                .with_retry_policy(8, Duration.ofMillis(1), Duration.ofMillis(4)));

        long started = System.nanoTime();
        MessageResponse response = client.send_message(sample_request(false));
        long elapsed_ms = (System.nanoTime() - started) / 1_000_000L;

        assertThat(response.total_tokens()).isEqualTo(5L);
        assertThat(server.captured()).hasSize(6);
        // Should comfortably finish well below 5 seconds (matches Rust expectation).
        assertThat(elapsed_ms).isLessThan(5_000L);
    }

    @Test
    void send_message_authentication_error_is_not_retried() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid key\"}}"));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("bad").with_base_url(server.base_url()));

        assertThatThrownBy(() -> client.send_message(sample_request(false)))
                .isInstanceOf(AnthropicException.class)
                .satisfies(error -> {
                    AnthropicException ex = (AnthropicException) error;
                    assertThat(ex.kind()).isEqualTo(AnthropicException.Kind.API);
                    assertThat(ex.status()).hasValue(401);
                    assertThat(ex.error_class()).hasValue(AnthropicException.ErrorClass.AUTHENTICATION_ERROR);
                    assertThat(ex.is_retryable()).isFalse();
                });
        assertThat(server.captured()).hasSize(1);
    }

    @Test
    void count_tokens_returns_input_tokens_from_response() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, "{\"input_tokens\":12345}"));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("test-key").with_base_url(server.base_url()));
        long count = client.count_tokens(sample_request(false));

        assertThat(count).isEqualTo(12345L);
        CapturingHttpServer.CapturedRequest captured = server.captured().get(0);
        assertThat(captured.path()).isEqualTo("/v1/messages/count_tokens");
    }

    @Test
    void count_tokens_propagates_retryable_failure() {
        server.enqueue(CapturingHttpServer.StagedResponse.json(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"));
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, "{\"input_tokens\":7}"));

        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_base_url(server.base_url())
                .with_retry_policy(2, Duration.ofMillis(1), Duration.ofMillis(2)));
        long count = client.count_tokens(sample_request(false));

        assertThat(count).isEqualTo(7L);
        assertThat(server.captured()).hasSize(2);
    }

    @Test
    void provider_client_dispatches_anthropic_requests_via_endpoint_path() throws IOException {
        String body = "{\"id\":\"msg_provider\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Dispatched\"}],"
                + "\"model\":\"claude-3-7-sonnet-latest\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        AnthropicClient client =
                new AnthropicClient(AnthropicConfig.of_api_key("test-key").with_base_url(server.base_url()));
        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.total_tokens()).isEqualTo(5L);

        CapturingHttpServer.CapturedRequest request = server.captured().get(0);
        assertThat(request.path()).isEqualTo("/v1/messages");
        assertThat(request.headers()).containsEntry("x-api-key", "test-key");
        // Confirm the request body still has stream=false and tool fields wired up.
        JsonNode body_json = MAPPER.readTree(request.body());
        assertThat(body_json.get("stream").asBoolean()).isFalse();
        assertThat(body_json.has("tools")).isTrue();
    }

    private static MessageRequest sample_request(boolean stream) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode city = MAPPER.createObjectNode();
        city.put("type", "string");
        properties.set("city", city);
        schema.set("properties", properties);
        schema.putArray("required").add("city");

        return MessageRequest.builder()
                .model("claude-3-7-sonnet-latest")
                .max_tokens(64)
                .messages(List.of(new InputMessage(
                        "user",
                        List.of(
                                new InputContentBlock.Text("Say hello"),
                                new InputContentBlock.ToolResult(
                                        "toolu_prev",
                                        List.of(new ToolResultContentBlock.Json(
                                                MAPPER.valueToTree(Map.of("forecast", "sunny")))),
                                        false)))))
                .system("Use tools when needed")
                .tools(List.of(new ToolDefinition("get_weather", "Fetches the weather", schema)))
                .tool_choice(ToolChoice.auto())
                .stream(stream)
                .build();
    }
}
