package org.jclaude.api.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.ToolChoice;
import org.jclaude.api.types.ToolDefinition;
import org.jclaude.api.types.ToolResultContentBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatClientTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    @AfterEach
    void clear_overrides() {
        Providers.ENV_OVERRIDES_FOR_TESTING.clear();
    }

    @Test
    void request_translation_uses_openai_compatible_shape() throws IOException {
        JsonNode input_schema = MAPPER.readTree("{\"type\":\"object\"}");
        MessageRequest request = MessageRequest.builder()
                .model("grok-3")
                .max_tokens(64)
                .messages(List.of(new InputMessage(
                        "user",
                        List.of(
                                new InputContentBlock.Text("hello"),
                                new InputContentBlock.ToolResult(
                                        "tool_1",
                                        List.of(new ToolResultContentBlock.Json(MAPPER.readTree("{\"ok\":true}"))),
                                        false)))))
                .system("be helpful")
                .tools(List.of(new ToolDefinition("weather", "Get weather", input_schema)))
                .tool_choice(ToolChoice.auto())
                .stream(false)
                .build();

        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.xai());

        assertThat(payload.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(payload.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(payload.get("messages").get(2).get("role").asText()).isEqualTo("tool");
        assertThat(payload.get("tools").get(0).get("type").asText()).isEqualTo("function");
        assertThat(payload.get("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void tool_schema_object_gets_strict_fields_for_responses_endpoint() throws IOException {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\"}");
        OpenAiCompatClient.normalize_object_schema(schema);
        assertThat(schema.get("properties")).isNotNull();
        assertThat(schema.get("properties").size()).isZero();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

        JsonNode schema2 = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"object\",\"properties\":{\"lat\":{\"type\":\"number\"}}}}}");
        OpenAiCompatClient.normalize_object_schema(schema2);
        assertThat(schema2.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema2.get("properties")
                        .get("location")
                        .get("additionalProperties")
                        .asBoolean())
                .isFalse();

        JsonNode schema3 = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"additionalProperties\":true}");
        OpenAiCompatClient.normalize_object_schema(schema3);
        assertThat(schema3.get("additionalProperties").asBoolean())
                .as("must not overwrite existing")
                .isTrue();
    }

    @Test
    void reasoning_effort_is_included_when_set() {
        MessageRequest request = MessageRequest.builder()
                .model("o4-mini")
                .max_tokens(1024)
                .messages(List.of(InputMessage.user_text("think hard")))
                .reasoning_effort("high")
                .build();

        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.get("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    void reasoning_effort_omitted_when_not_set() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(64)
                .messages(List.of(InputMessage.user_text("hello")))
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.has("reasoning_effort")).isFalse();
    }

    @Test
    void openai_streaming_requests_include_usage_opt_in() {
        MessageRequest request =
                MessageRequest.builder()
                        .model("gpt-5")
                        .max_tokens(64)
                        .messages(List.of(InputMessage.user_text("hello")))
                        .stream(true)
                        .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.get("stream_options").get("include_usage").asBoolean())
                .isTrue();
    }

    @Test
    void xai_streaming_requests_skip_openai_specific_usage_opt_in() {
        MessageRequest request =
                MessageRequest.builder()
                        .model("grok-3")
                        .max_tokens(64)
                        .messages(List.of(InputMessage.user_text("hello")))
                        .stream(true)
                        .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.xai());
        assertThat(payload.has("stream_options")).isFalse();
    }

    @Test
    void tool_choice_translation_supports_required_function() {
        MessageRequest request_any = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(64)
                .messages(List.of())
                .tool_choice(ToolChoice.any())
                .build();
        JsonNode payload_any =
                OpenAiCompatClient.build_chat_completion_request(request_any, OpenAiCompatConfig.openai());
        assertThat(payload_any.get("tool_choice").asText()).isEqualTo("required");

        MessageRequest request_tool = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(64)
                .messages(List.of())
                .tool_choice(ToolChoice.tool("weather"))
                .build();
        JsonNode payload_tool =
                OpenAiCompatClient.build_chat_completion_request(request_tool, OpenAiCompatConfig.openai());
        assertThat(payload_tool.get("tool_choice").get("type").asText()).isEqualTo("function");
        assertThat(payload_tool.get("tool_choice").get("function").get("name").asText())
                .isEqualTo("weather");
    }

    @Test
    void parses_tool_arguments_fallback() throws IOException {
        JsonNode parsed = OpenAiCompatClient.parse_tool_arguments("{\"city\":\"Paris\"}");
        assertThat(parsed.get("city").asText()).isEqualTo("Paris");

        JsonNode raw = OpenAiCompatClient.parse_tool_arguments("not-json");
        assertThat(raw.get("raw").asText()).isEqualTo("not-json");
    }

    @Test
    void missing_xai_api_key_is_provider_specific() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");

        assertThatThrownBy(() -> OpenAiCompatClient.from_env(OpenAiCompatConfig.xai()))
                .isInstanceOf(OpenAiCompatException.class)
                .satisfies(error -> {
                    OpenAiCompatException ex = (OpenAiCompatException) error;
                    assertThat(ex.kind()).isEqualTo(OpenAiCompatException.Kind.MISSING_CREDENTIALS);
                    assertThat(ex.getMessage()).contains("xAI");
                });
    }

    @Test
    void endpoint_builder_accepts_base_urls_and_full_endpoints() {
        assertThat(OpenAiCompatClient.chat_completions_endpoint("https://api.x.ai/v1"))
                .isEqualTo("https://api.x.ai/v1/chat/completions");
        assertThat(OpenAiCompatClient.chat_completions_endpoint("https://api.x.ai/v1/"))
                .isEqualTo("https://api.x.ai/v1/chat/completions");
        assertThat(OpenAiCompatClient.chat_completions_endpoint("https://api.x.ai/v1/chat/completions"))
                .isEqualTo("https://api.x.ai/v1/chat/completions");
    }

    @Test
    void normalizes_stop_reasons() {
        assertThat(OpenAiCompatClient.normalize_finish_reason("stop")).isEqualTo("end_turn");
        assertThat(OpenAiCompatClient.normalize_finish_reason("tool_calls")).isEqualTo("tool_use");
    }

    @Test
    void tuning_params_included_in_payload_when_set() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(1024)
                .messages(List.of())
                .temperature(0.7)
                .top_p(0.9)
                .frequency_penalty(0.5)
                .presence_penalty(0.3)
                .stop(List.of("\n"))
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(payload.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(payload.get("frequency_penalty").asDouble()).isEqualTo(0.5);
        assertThat(payload.get("presence_penalty").asDouble()).isEqualTo(0.3);
        assertThat(payload.get("stop").get(0).asText()).isEqualTo("\n");
    }

    @Test
    void reasoning_model_strips_tuning_params() {
        MessageRequest request = MessageRequest.builder()
                .model("o1-mini")
                .max_tokens(1024)
                .messages(List.of())
                .temperature(0.7)
                .top_p(0.9)
                .frequency_penalty(0.5)
                .presence_penalty(0.3)
                .stop(List.of("\n"))
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.has("temperature")).isFalse();
        assertThat(payload.has("top_p")).isFalse();
        assertThat(payload.has("frequency_penalty")).isFalse();
        assertThat(payload.has("presence_penalty")).isFalse();
        assertThat(payload.get("stop").get(0).asText()).isEqualTo("\n");
    }

    @Test
    void grok_3_mini_is_reasoning_model() {
        assertThat(OpenAiCompatClient.is_reasoning_model("grok-3-mini")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("o1")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("o1-mini")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("o3-mini")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("gpt-4o")).isFalse();
        assertThat(OpenAiCompatClient.is_reasoning_model("grok-3")).isFalse();
        assertThat(OpenAiCompatClient.is_reasoning_model("claude-sonnet-4-6")).isFalse();
    }

    @Test
    void qwen_reasoning_variants_are_detected() {
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen-qwq-32b")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen/qwen-qwq-32b")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen3-30b-a3b-thinking"))
                .isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen/qwen3-30b-a3b-thinking"))
                .isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwq-plus")).isTrue();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen-max")).isFalse();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen/qwen-plus")).isFalse();
        assertThat(OpenAiCompatClient.is_reasoning_model("qwen-turbo")).isFalse();
    }

    @Test
    void tuning_params_omitted_from_payload_when_none() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(1024)
                .messages(List.of())
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.has("temperature")).isFalse();
        assertThat(payload.has("top_p")).isFalse();
        assertThat(payload.has("frequency_penalty")).isFalse();
        assertThat(payload.has("presence_penalty")).isFalse();
        assertThat(payload.has("stop")).isFalse();
    }

    @Test
    void gpt5_uses_max_completion_tokens_not_max_tokens() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-5.2")
                .max_tokens(512)
                .messages(List.of())
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.get("max_completion_tokens").asLong()).isEqualTo(512L);
        assertThat(payload.has("max_tokens")).isFalse();
    }

    @Test
    void assistant_message_without_tool_calls_omits_tool_calls_field() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(100)
                .messages(List.of(InputMessage.assistant(List.of(new InputContentBlock.Text("Hello")))))
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        JsonNode messages = payload.get("messages");
        JsonNode assistant = null;
        for (JsonNode msg : messages) {
            if ("assistant".equals(msg.get("role").asText())) {
                assistant = msg;
                break;
            }
        }
        assertThat(assistant).isNotNull();
        assertThat(assistant.has("tool_calls")).isFalse();
    }

    @Test
    void assistant_message_with_tool_calls_includes_tool_calls_field() throws IOException {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(100)
                .messages(List.of(InputMessage.assistant(List.of(new InputContentBlock.ToolUse(
                        "call_1", "read_file", MAPPER.readTree("{\"path\":\"/tmp/test\"}"))))))
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        JsonNode messages = payload.get("messages");
        JsonNode assistant = null;
        for (JsonNode msg : messages) {
            if ("assistant".equals(msg.get("role").asText())) {
                assistant = msg;
                break;
            }
        }
        assertThat(assistant).isNotNull();
        JsonNode tool_calls = assistant.get("tool_calls");
        assertThat(tool_calls).isNotNull();
        assertThat(tool_calls.isArray()).isTrue();
        assertThat(tool_calls.size()).isEqualTo(1);
    }

    @Test
    void sanitize_drops_orphaned_tool_messages() throws IOException {
        // Valid pair
        ObjectNode assistant = MAPPER.createObjectNode();
        assistant.put("role", "assistant");
        assistant.set("content", MAPPER.nullNode());
        assistant.set(
                "tool_calls",
                MAPPER.readTree(
                        "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]"));
        ObjectNode tool_msg = MAPPER.createObjectNode();
        tool_msg.put("role", "tool");
        tool_msg.put("tool_call_id", "call_1");
        tool_msg.put("content", "result");
        var valid = MAPPER.createArrayNode();
        valid.add(assistant.deepCopy());
        valid.add(tool_msg.deepCopy());
        var out = OpenAiCompatClient.sanitize_tool_message_pairing(valid);
        assertThat(out.size()).isEqualTo(2);

        // Orphaned tool message: no preceding assistant tool_calls
        var orphaned = MAPPER.createArrayNode();
        ObjectNode plain_assistant = MAPPER.createObjectNode();
        plain_assistant.put("role", "assistant");
        plain_assistant.put("content", "hi");
        ObjectNode orphan_tool = MAPPER.createObjectNode();
        orphan_tool.put("role", "tool");
        orphan_tool.put("tool_call_id", "call_2");
        orphan_tool.put("content", "orphaned");
        orphaned.add(plain_assistant);
        orphaned.add(orphan_tool);
        var out2 = OpenAiCompatClient.sanitize_tool_message_pairing(orphaned);
        assertThat(out2.size()).isEqualTo(1);
        assertThat(out2.get(0).get("role").asText()).isEqualTo("assistant");

        // Mismatched tool_call_id
        var mismatched = MAPPER.createArrayNode();
        ObjectNode mismatch_assistant = MAPPER.createObjectNode();
        mismatch_assistant.put("role", "assistant");
        mismatch_assistant.set("content", MAPPER.nullNode());
        mismatch_assistant.set(
                "tool_calls",
                MAPPER.readTree(
                        "[{\"id\":\"call_3\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]"));
        ObjectNode wrong_tool = MAPPER.createObjectNode();
        wrong_tool.put("role", "tool");
        wrong_tool.put("tool_call_id", "call_WRONG");
        wrong_tool.put("content", "bad");
        mismatched.add(mismatch_assistant);
        mismatched.add(wrong_tool);
        var out3 = OpenAiCompatClient.sanitize_tool_message_pairing(mismatched);
        assertThat(out3.size()).isEqualTo(1);

        // Two tool results both valid
        var two = MAPPER.createArrayNode();
        ObjectNode two_assistant = MAPPER.createObjectNode();
        two_assistant.put("role", "assistant");
        two_assistant.set("content", MAPPER.nullNode());
        two_assistant.set(
                "tool_calls",
                MAPPER.readTree(
                        "[{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"fa\",\"arguments\":\"{}\"}},"
                                + "{\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"fb\",\"arguments\":\"{}\"}}]"));
        ObjectNode tool_a = MAPPER.createObjectNode();
        tool_a.put("role", "tool");
        tool_a.put("tool_call_id", "call_a");
        tool_a.put("content", "ra");
        ObjectNode tool_b = MAPPER.createObjectNode();
        tool_b.put("role", "tool");
        tool_b.put("tool_call_id", "call_b");
        tool_b.put("content", "rb");
        two.add(two_assistant);
        two.add(tool_a);
        two.add(tool_b);
        var out4 = OpenAiCompatClient.sanitize_tool_message_pairing(two);
        assertThat(out4.size()).isEqualTo(3);
    }

    @Test
    void non_gpt5_uses_max_tokens() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(512)
                .messages(List.of())
                .build();
        JsonNode payload = OpenAiCompatClient.build_chat_completion_request(request, OpenAiCompatConfig.openai());
        assertThat(payload.get("max_tokens").asLong()).isEqualTo(512L);
        assertThat(payload.has("max_completion_tokens")).isFalse();
    }

    @Test
    void model_rejects_is_error_field_detects_kimi_models() {
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("kimi-k2.5")).isTrue();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("kimi-k1.5")).isTrue();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("kimi-moonshot"))
                .isTrue();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("KIMI-K2.5")).isTrue();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("dashscope/kimi-k2.5"))
                .isTrue();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("moonshot/kimi-k2.5"))
                .isTrue();

        assertThat(OpenAiCompatClient.model_rejects_is_error_field("gpt-4o")).isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("gpt-4")).isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("claude-sonnet-4-6"))
                .isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("grok-3")).isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("grok-3-mini"))
                .isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("xai/grok-3"))
                .isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("qwen/qwen-plus"))
                .isFalse();
        assertThat(OpenAiCompatClient.model_rejects_is_error_field("o1-mini")).isFalse();
    }

    @Test
    void translate_message_includes_is_error_for_non_kimi_models() {
        InputMessage message = new InputMessage(
                "user",
                List.of(new InputContentBlock.ToolResult(
                        "call_1", List.of(new ToolResultContentBlock.Text("Error occurred")), true)));

        List<JsonNode> translated = OpenAiCompatClient.translate_message(message, "gpt-4o");
        assertThat(translated).hasSize(1);
        JsonNode tool_msg = translated.get(0);
        assertThat(tool_msg.get("role").asText()).isEqualTo("tool");
        assertThat(tool_msg.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool_msg.get("content").asText()).isEqualTo("Error occurred");
        assertThat(tool_msg.has("is_error")).isTrue();
        assertThat(tool_msg.get("is_error").asBoolean()).isTrue();

        InputMessage message2 = new InputMessage(
                "user",
                List.of(new InputContentBlock.ToolResult(
                        "call_2", List.of(new ToolResultContentBlock.Text("Success")), false)));
        List<JsonNode> translated2 = OpenAiCompatClient.translate_message(message2, "grok-3");
        assertThat(translated2.get(0).has("is_error")).isTrue();
        assertThat(translated2.get(0).get("is_error").asBoolean()).isFalse();

        List<JsonNode> translated3 = OpenAiCompatClient.translate_message(message, "claude-sonnet-4-6");
        assertThat(translated3.get(0).has("is_error")).isTrue();
    }

    @Test
    void translate_message_excludes_is_error_for_kimi_models() {
        InputMessage message = new InputMessage(
                "user",
                List.of(new InputContentBlock.ToolResult(
                        "call_1", List.of(new ToolResultContentBlock.Text("Error occurred")), true)));

        List<JsonNode> translated = OpenAiCompatClient.translate_message(message, "kimi-k2.5");
        assertThat(translated).hasSize(1);
        JsonNode tool_msg = translated.get(0);
        assertThat(tool_msg.get("role").asText()).isEqualTo("tool");
        assertThat(tool_msg.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool_msg.get("content").asText()).isEqualTo("Error occurred");
        assertThat(tool_msg.has("is_error")).isFalse();

        List<JsonNode> translated2 = OpenAiCompatClient.translate_message(message, "kimi-k1.5");
        assertThat(translated2.get(0).has("is_error")).isFalse();

        List<JsonNode> translated3 = OpenAiCompatClient.translate_message(message, "dashscope/kimi-k2.5");
        assertThat(translated3.get(0).has("is_error")).isFalse();
    }

    @Test
    void build_chat_completion_request_kimi_vs_non_kimi_tool_results() throws IOException {
        MessageRequest request_gpt = make_tool_request("gpt-4o");
        JsonNode payload_gpt =
                OpenAiCompatClient.build_chat_completion_request(request_gpt, OpenAiCompatConfig.openai());
        JsonNode tool_msg_gpt = first_tool_message(payload_gpt);
        assertThat(tool_msg_gpt.has("is_error")).isTrue();

        MessageRequest request_kimi = make_tool_request("kimi-k2.5");
        JsonNode payload_kimi =
                OpenAiCompatClient.build_chat_completion_request(request_kimi, OpenAiCompatConfig.dashscope());
        JsonNode tool_msg_kimi = first_tool_message(payload_kimi);
        assertThat(tool_msg_kimi.has("is_error")).isFalse();

        assertThat(tool_msg_gpt.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool_msg_kimi.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool_msg_gpt.get("content").asText()).isEqualTo("file contents");
        assertThat(tool_msg_kimi.get("content").asText()).isEqualTo("file contents");
    }

    private static MessageRequest make_tool_request(String model) throws IOException {
        return MessageRequest.builder()
                .model(model)
                .max_tokens(100)
                .messages(List.of(
                        InputMessage.assistant(List.of(new InputContentBlock.ToolUse(
                                "call_1", "read_file", MAPPER.readTree("{\"path\":\"/tmp/test\"}")))),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "call_1", List.of(new ToolResultContentBlock.Text("file contents")), false)))))
                .build();
    }

    private static JsonNode first_tool_message(JsonNode payload) {
        for (JsonNode msg : payload.get("messages")) {
            if ("tool".equals(msg.get("role").asText())) {
                return msg;
            }
        }
        throw new AssertionError("no tool message in payload");
    }

    @Test
    void estimate_request_body_size_returns_reasonable_estimate() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(100)
                .messages(List.of(InputMessage.user_text("Hello world")))
                .build();
        int size = OpenAiCompatClient.estimate_request_body_size(request, OpenAiCompatConfig.openai());
        assertThat(size).isPositive();
        assertThat(size).isLessThan(10_000);
    }

    @Test
    void check_request_body_size_passes_for_small_requests() {
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(100)
                .messages(List.of(InputMessage.user_text("Hello")))
                .build();

        OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.openai());
        OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.xai());
        OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.dashscope());
    }

    @Test
    void check_request_body_size_fails_for_dashscope_when_exceeds_6mb() {
        String large = "x".repeat(7_000_000);
        MessageRequest request = MessageRequest.builder()
                .model("qwen-plus")
                .max_tokens(100)
                .messages(List.of(InputMessage.user_text(large)))
                .build();

        assertThatThrownBy(() -> OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.dashscope()))
                .isInstanceOf(OpenAiCompatException.class)
                .satisfies(error -> {
                    OpenAiCompatException ex = (OpenAiCompatException) error;
                    assertThat(ex.kind()).isEqualTo(OpenAiCompatException.Kind.REQUEST_BODY_SIZE_EXCEEDED);
                    assertThat(ex.getMessage()).contains("DashScope");
                    assertThat(ex.getMessage()).contains("6291456");
                });
    }

    @Test
    void check_request_body_size_allows_large_requests_for_openai() {
        String large = "x".repeat(10_000_000);
        MessageRequest request = MessageRequest.builder()
                .model("gpt-4o")
                .max_tokens(100)
                .messages(List.of(InputMessage.user_text(large)))
                .build();

        OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.openai());
        assertThatThrownBy(() -> OpenAiCompatClient.check_request_body_size(request, OpenAiCompatConfig.dashscope()))
                .isInstanceOf(OpenAiCompatException.class);
    }

    @Test
    void provider_specific_size_limits_are_correct() {
        assertThat(OpenAiCompatConfig.dashscope().max_request_body_bytes()).isEqualTo(6_291_456);
        assertThat(OpenAiCompatConfig.openai().max_request_body_bytes()).isEqualTo(104_857_600);
        assertThat(OpenAiCompatConfig.xai().max_request_body_bytes()).isEqualTo(52_428_800);
    }

    @Test
    void strip_routing_prefix_strips_kimi_provider_prefix() {
        assertThat(OpenAiCompatClient.strip_routing_prefix("kimi/kimi-k2.5")).isEqualTo("kimi-k2.5");
        assertThat(OpenAiCompatClient.strip_routing_prefix("kimi-k2.5")).isEqualTo("kimi-k2.5");
        assertThat(OpenAiCompatClient.strip_routing_prefix("kimi/kimi-k1.5")).isEqualTo("kimi-k1.5");
    }

    @Test
    void delta_with_null_tool_calls_deserializes_as_empty_vec() throws IOException {
        // Regression: gaebal-gajae 2026-04-09 — deltas with tool_calls:null must not
        // crash the streaming parser. The Java port uses Jackson which already treats
        // null as "no value"; we exercise the live ChunkParser → StreamState pipeline
        // to confirm a chunk with tool_calls:null produces no errors and no tool events.
        String chunk_json = "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\","
                + "\"function_call\":null,\"refusal\":null,\"role\":\"assistant\","
                + "\"tool_calls\":null}}]}\n\n";
        ObjectMapper mapper = JclaudeMappers.standard();
        java.util.Optional<JsonNode> parsed = OpenAiCompatClient.parse_sse_frame(chunk_json, "openai", "gpt-4o");

        assertThat(parsed).isPresent();
        JsonNode delta = parsed.get().get("choices").get(0).get("delta");
        // tool_calls is present but null; the streaming pipeline must treat this as
        // an empty list (mirroring the Rust deserialize_null_as_empty_vec helper).
        assertThat(delta.get("tool_calls").isNull()).isTrue();

        // Driving the chunk through StreamState should not throw and produce no
        // ContentBlockStart events for tool calls.
        OpenAiCompatClient.StreamState state = new OpenAiCompatClient.StreamState("gpt-4o");
        java.util.List<StreamEvent> events = state.ingest_chunk(parsed.get());
        long tool_block_starts = events.stream()
                .filter(e -> e instanceof StreamEvent.ContentBlockStart cbs
                        && cbs.content_block() instanceof OutputContentBlock.ToolUse)
                .count();
        assertThat(tool_block_starts).isZero();
    }
}
