package org.jclaude.api.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.MessageResponse;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.ToolChoice;
import org.jclaude.api.types.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatIntegrationTest {

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
    void send_message_uses_openai_compatible_endpoint_and_auth() throws IOException {
        String body = "{"
                + "\"id\":\"chatcmpl_test\","
                + "\"model\":\"grok-3\","
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello from Grok\",\"tool_calls\":[]},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}"
                + "}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        OpenAiCompatClient client =
                new OpenAiCompatClient("xai-test-key", OpenAiCompatConfig.xai()).with_base_url(server.base_url());
        MessageResponse response = client.send_message(sample_request(false));

        assertThat(response.model()).isEqualTo("grok-3");
        assertThat(response.total_tokens()).isEqualTo(16L);
        assertThat(response.content()).containsExactly(new OutputContentBlock.Text("Hello from Grok"));

        List<CapturingHttpServer.CapturedRequest> captured = server.captured();
        assertThat(captured).hasSize(1);
        CapturingHttpServer.CapturedRequest request = captured.get(0);
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.headers()).containsEntry("authorization", "Bearer xai-test-key");
        JsonNode parsed_body = MAPPER.readTree(request.body());
        assertThat(parsed_body.get("model").asText()).isEqualTo("grok-3");
        assertThat(parsed_body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(parsed_body.get("tools").get(0).get("type").asText()).isEqualTo("function");
    }

    @Test
    void send_message_blocks_oversized_xai_requests_before_the_http_call() {
        OpenAiCompatClient client =
                new OpenAiCompatClient("xai-test-key", OpenAiCompatConfig.xai()).with_base_url(server.base_url());
        MessageRequest oversized = MessageRequest.builder()
                .model("grok-3")
                .max_tokens(64_000)
                .messages(List.of(InputMessage.user_text("x".repeat(300_000))))
                .system("Keep the answer short.")
                .build();

        assertThatThrownBy(() -> client.send_message(oversized))
                .isInstanceOf(OpenAiCompatException.class)
                .satisfies(error -> {
                    OpenAiCompatException ex = (OpenAiCompatException) error;
                    assertThat(ex.kind()).isEqualTo(OpenAiCompatException.Kind.CONTEXT_WINDOW_EXCEEDED);
                });
        assertThat(server.captured()).isEmpty();
    }

    @Test
    void send_message_accepts_full_chat_completions_endpoint_override() throws IOException {
        String body = "{"
                + "\"id\":\"chatcmpl_full_endpoint\","
                + "\"model\":\"grok-3\","
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Endpoint override works\",\"tool_calls\":[]},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}"
                + "}";
        server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

        String endpoint_url = server.base_url() + "/chat/completions";
        OpenAiCompatClient client =
                new OpenAiCompatClient("xai-test-key", OpenAiCompatConfig.xai()).with_base_url(endpoint_url);
        MessageResponse response = client.send_message(sample_request(false));
        assertThat(response.total_tokens()).isEqualTo(10L);

        assertThat(server.captured()).hasSize(1);
        assertThat(server.captured().get(0).path()).isEqualTo("/chat/completions");
    }

    @Test
    void stream_message_normalizes_text_and_multiple_tool_calls() {
        String sse =
                "data: {\"id\":\"chatcmpl_stream\",\"model\":\"grok-3\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                        + "data: {\"id\":\"chatcmpl_stream\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}},{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"clock\",\"arguments\":\"{\\\"zone\\\":\\\"UTC\\\"}\"}}]}}]}\n\n"
                        + "data: {\"id\":\"chatcmpl_stream\",\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n";
        server.enqueue(CapturingHttpServer.StagedResponse.sse(sse, Map.of("x-request-id", "req_grok_stream")));

        OpenAiCompatClient client =
                new OpenAiCompatClient("xai-test-key", OpenAiCompatConfig.xai()).with_base_url(server.base_url());
        OpenAiCompatClient.StreamingResponse stream = client.stream_message(sample_request(false));

        assertThat(stream.request_id()).hasValue("req_grok_stream");

        List<StreamEvent> events = new ArrayList<>();
        stream.iterator().forEachRemaining(events::add);

        assertThat(events.get(0)).isInstanceOf(StreamEvent.MessageStart.class);
        StreamEvent.ContentBlockStart text_start = (StreamEvent.ContentBlockStart) events.get(1);
        assertThat(text_start.content_block()).isInstanceOf(OutputContentBlock.Text.class);
        StreamEvent.ContentBlockDelta text_delta = (StreamEvent.ContentBlockDelta) events.get(2);
        assertThat(text_delta.delta()).isInstanceOf(BlockDelta.TextDelta.class);

        StreamEvent.ContentBlockStart tool_a_start = (StreamEvent.ContentBlockStart) events.get(3);
        assertThat(tool_a_start.index()).isEqualTo(1);
        assertThat(tool_a_start.content_block()).isInstanceOf(OutputContentBlock.ToolUse.class);
        StreamEvent.ContentBlockDelta tool_a_delta = (StreamEvent.ContentBlockDelta) events.get(4);
        assertThat(tool_a_delta.index()).isEqualTo(1);
        assertThat(tool_a_delta.delta()).isInstanceOf(BlockDelta.InputJsonDelta.class);

        StreamEvent.ContentBlockStart tool_b_start = (StreamEvent.ContentBlockStart) events.get(5);
        assertThat(tool_b_start.index()).isEqualTo(2);
        assertThat(tool_b_start.content_block()).isInstanceOf(OutputContentBlock.ToolUse.class);
        StreamEvent.ContentBlockDelta tool_b_delta = (StreamEvent.ContentBlockDelta) events.get(6);
        assertThat(tool_b_delta.index()).isEqualTo(2);
        assertThat(tool_b_delta.delta()).isInstanceOf(BlockDelta.InputJsonDelta.class);

        StreamEvent.ContentBlockStop stop_a = (StreamEvent.ContentBlockStop) events.get(7);
        assertThat(stop_a.index()).isEqualTo(1);
        StreamEvent.ContentBlockStop stop_b = (StreamEvent.ContentBlockStop) events.get(8);
        assertThat(stop_b.index()).isEqualTo(2);
        StreamEvent.ContentBlockStop stop_text = (StreamEvent.ContentBlockStop) events.get(9);
        assertThat(stop_text.index()).isEqualTo(0);

        assertThat(events.get(10)).isInstanceOf(StreamEvent.MessageDelta.class);
        assertThat(events.get(11)).isInstanceOf(StreamEvent.MessageStop.class);

        List<CapturingHttpServer.CapturedRequest> captured = server.captured();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).path()).isEqualTo("/chat/completions");
        assertThat(captured.get(0).body()).contains("\"stream\":true");
    }

    @Test
    void openai_streaming_requests_opt_into_usage_chunks() throws IOException {
        String sse =
                "data: {\"id\":\"chatcmpl_openai_stream\",\"model\":\"gpt-5\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
                        + "data: {\"id\":\"chatcmpl_openai_stream\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"id\":\"chatcmpl_openai_stream\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}\n\n"
                        + "data: [DONE]\n\n";
        server.enqueue(CapturingHttpServer.StagedResponse.sse(sse, Map.of("x-request-id", "req_openai_stream")));

        OpenAiCompatClient client =
                new OpenAiCompatClient("openai-test-key", OpenAiCompatConfig.openai()).with_base_url(server.base_url());
        OpenAiCompatClient.StreamingResponse stream = client.stream_message(sample_request(false));

        assertThat(stream.request_id()).hasValue("req_openai_stream");

        List<StreamEvent> events = new ArrayList<>();
        stream.iterator().forEachRemaining(events::add);

        assertThat(events.get(0)).isInstanceOf(StreamEvent.MessageStart.class);
        StreamEvent.ContentBlockStart text_start = (StreamEvent.ContentBlockStart) events.get(1);
        assertThat(text_start.content_block()).isInstanceOf(OutputContentBlock.Text.class);
        StreamEvent.ContentBlockDelta delta = (StreamEvent.ContentBlockDelta) events.get(2);
        assertThat(delta.delta()).isInstanceOf(BlockDelta.TextDelta.class);
        StreamEvent.ContentBlockStop text_stop = (StreamEvent.ContentBlockStop) events.get(3);
        assertThat(text_stop.index()).isEqualTo(0);

        StreamEvent.MessageDelta message_delta = (StreamEvent.MessageDelta) events.get(4);
        assertThat(message_delta.usage().input_tokens()).isEqualTo(9L);
        assertThat(message_delta.usage().output_tokens()).isEqualTo(4L);
        assertThat(events.get(5)).isInstanceOf(StreamEvent.MessageStop.class);

        List<CapturingHttpServer.CapturedRequest> captured = server.captured();
        assertThat(captured).hasSize(1);
        CapturingHttpServer.CapturedRequest request = captured.get(0);
        assertThat(request.path()).isEqualTo("/chat/completions");
        JsonNode parsed_body = MAPPER.readTree(request.body());
        assertThat(parsed_body.get("stream").asBoolean()).isTrue();
        assertThat(parsed_body.get("stream_options").get("include_usage").asBoolean())
                .isTrue();
    }

    @Test
    void provider_client_dispatches_xai_requests_from_env() throws IOException {
        // Simulate the XAI_API_KEY being present in the env via the testing override hook.
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "xai-test-key");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_BASE_URL", server.base_url());
        try {
            String body = "{"
                    + "\"id\":\"chatcmpl_provider\",\"model\":\"grok-3\","
                    + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Through provider client\",\"tool_calls\":[]},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}";
            server.enqueue(CapturingHttpServer.StagedResponse.json(200, body));

            // detect_provider_kind should report XAI for "grok"
            assertThat(Providers.detect_provider_kind("grok")).isEqualTo(ProviderKind.XAI);
            // Build the client through the from_env path
            OpenAiCompatClient client = OpenAiCompatClient.from_env(OpenAiCompatConfig.xai());
            MessageResponse response = client.send_message(sample_request(false));
            assertThat(response.total_tokens()).isEqualTo(13L);

            List<CapturingHttpServer.CapturedRequest> captured = server.captured();
            assertThat(captured).hasSize(1);
            CapturingHttpServer.CapturedRequest request = captured.get(0);
            assertThat(request.path()).isEqualTo("/chat/completions");
            assertThat(request.headers()).containsEntry("authorization", "Bearer xai-test-key");
        } finally {
            Providers.ENV_OVERRIDES_FOR_TESTING.clear();
        }
    }

    private static MessageRequest sample_request(boolean stream) {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var properties = MAPPER.createObjectNode();
        var city = MAPPER.createObjectNode();
        city.put("type", "string");
        properties.set("city", city);
        schema.set("properties", properties);
        var required = MAPPER.createArrayNode();
        required.add("city");
        schema.set("required", required);

        return MessageRequest.builder()
                .model("grok-3")
                .max_tokens(64)
                .messages(List.of(InputMessage.user_text("Say hello")))
                .system("Use tools when needed")
                .tools(List.of(new ToolDefinition("weather", "Fetches weather", schema)))
                .tool_choice(ToolChoice.auto())
                .stream(stream)
                .build();
    }
}
