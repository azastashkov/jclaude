package org.jclaude.mockanthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.ToolResultContentBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockAnthropicServiceTest {

    private MockAnthropicService service;
    private final ObjectMapper mapper = JclaudeMappers.standard();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start_service() throws IOException {
        service = MockAnthropicService.spawn();
    }

    @AfterEach
    void stop_service() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void base_url_uses_loopback_and_ephemeral_port() {
        assertThat(service.base_url()).startsWith("http://127.0.0.1:");
        int port = Integer.parseInt(service.base_url().substring("http://127.0.0.1:".length()));
        assertThat(port).isPositive();
    }

    @Test
    void streaming_text_response_has_expected_sse_prefix() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("streaming_text", true));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValue("text/event-stream");
        assertThat(response.headers().firstValue("x-request-id")).hasValue("req_streaming_text");
        assertThat(response.body())
                .startsWith("event: message_start\n")
                .contains("\"type\":\"message_start\"")
                .contains("\"id\":\"msg_streaming_text\"")
                .contains("\"text\":\"Mock streaming \"")
                .contains("\"text\":\"says hello from the parity harness.\"")
                .endsWith("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
    }

    @Test
    void non_streaming_text_response_is_well_formed_json() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("streaming_text", false));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValue("application/json");
        assertThat(response.headers().firstValue("request-id")).hasValue("req_streaming_text");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.get("id").asText()).isEqualTo("msg_streaming_text");
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(body.get("stop_reason").asText()).isEqualTo("end_turn");
        assertThat(body.get("content").get(0).get("text").asText())
                .isEqualTo("Mock streaming says hello from the parity harness.");
    }

    @Test
    void read_file_roundtrip_first_iteration_emits_tool_use() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("read_file_roundtrip", true));
        assertThat(response.body())
                .contains("\"type\":\"tool_use\"")
                .contains("\"id\":\"toolu_read_fixture\"")
                .contains("\"name\":\"read_file\"")
                .contains("{\\\"path\\\":\\\"fixture.txt\\\"}");
    }

    @Test
    void read_file_roundtrip_second_iteration_emits_final_text() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:read_file_roundtrip"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_read_fixture",
                                        List.of(new ToolResultContentBlock.Text(
                                                "{\"file\":{\"content\":\"fixture data\"}}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("read_file roundtrip complete: fixture data");
    }

    @Test
    void grep_chunk_assembly_first_iteration_streams_partial_json_chunks() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("grep_chunk_assembly", true));
        assertThat(response.body())
                .contains("\"name\":\"grep_search\"")
                .contains("\"partial_json\":\"{\\\"pattern\\\":\\\"par\"")
                .contains("\"partial_json\":\"ity\\\",\\\"path\\\":\\\"fixture.txt\\\"\"")
                .contains("\"partial_json\":\",\\\"output_mode\\\":\\\"count\\\"}\"");
    }

    @Test
    void grep_chunk_assembly_second_iteration_uses_num_matches() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:grep_chunk_assembly"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_grep_fixture",
                                        List.of(new ToolResultContentBlock.Text("{\"numMatches\":7}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("grep_search matched 7 occurrences");
    }

    @Test
    void write_file_allowed_first_iteration_emits_write_file_tool_use() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("write_file_allowed", true));
        assertThat(response.body())
                .contains("\"id\":\"toolu_write_allowed\"")
                .contains("\"name\":\"write_file\"")
                .contains("generated/output.txt")
                .contains("created by mock service");
    }

    @Test
    void write_file_allowed_second_iteration_extracts_file_path() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:write_file_allowed"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_write_allowed",
                                        List.of(new ToolResultContentBlock.Text("{\"filePath\":\"/tmp/out.txt\"}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("write_file succeeded: /tmp/out.txt");
    }

    @Test
    void write_file_denied_first_iteration_emits_tool_use() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("write_file_denied", true));
        assertThat(response.body()).contains("\"id\":\"toolu_write_denied\"").contains("generated/denied.txt");
    }

    @Test
    void write_file_denied_second_iteration_passes_through_error() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:write_file_denied"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_write_denied",
                                        List.of(new ToolResultContentBlock.Text("read-only mode")),
                                        true)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("write_file denied as expected: read-only mode");
    }

    @Test
    void multi_tool_turn_first_iteration_emits_two_tool_uses() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("multi_tool_turn_roundtrip", true));
        assertThat(response.body())
                .contains("\"id\":\"toolu_multi_read\"")
                .contains("\"id\":\"toolu_multi_grep\"")
                .contains("\"name\":\"read_file\"")
                .contains("\"name\":\"grep_search\"");
    }

    @Test
    void multi_tool_turn_second_iteration_combines_both_tool_results() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:multi_tool_turn_roundtrip"),
                        InputMessage.assistant(List.of(
                                new InputContentBlock.ToolUse(
                                        "toolu_multi_read", "read_file", mapper.createObjectNode()),
                                new InputContentBlock.ToolUse(
                                        "toolu_multi_grep", "grep_search", mapper.createObjectNode()))),
                        new InputMessage(
                                "user",
                                List.of(
                                        new InputContentBlock.ToolResult(
                                                "toolu_multi_read",
                                                List.of(new ToolResultContentBlock.Text(
                                                        "{\"file\":{\"content\":\"abc\"}}")),
                                                false),
                                        new InputContentBlock.ToolResult(
                                                "toolu_multi_grep",
                                                List.of(new ToolResultContentBlock.Text("{\"numMatches\":3}")),
                                                false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("multi-tool roundtrip complete: abc / 3 occurrences");
    }

    @Test
    void bash_stdout_roundtrip_first_iteration_emits_bash_tool_use() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("bash_stdout_roundtrip", true));
        assertThat(response.body())
                .contains("\"id\":\"toolu_bash_stdout\"")
                .contains("\"name\":\"bash\"")
                .contains("printf 'alpha from bash'");
    }

    @Test
    void bash_stdout_roundtrip_second_iteration_extracts_stdout() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:bash_stdout_roundtrip"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_bash_stdout",
                                        List.of(new ToolResultContentBlock.Text("{\"stdout\":\"alpha from bash\"}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("bash completed: alpha from bash");
    }

    @Test
    void bash_permission_prompt_approved_with_success_returns_executed_text() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:bash_permission_prompt_approved"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_bash_prompt_allow",
                                        List.of(new ToolResultContentBlock.Text(
                                                "{\"stdout\":\"approved via prompt\"}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("bash approved and executed: approved via prompt");
    }

    @Test
    void bash_permission_prompt_approved_with_error_returns_unexpected_failure() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:bash_permission_prompt_approved"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_bash_prompt_allow",
                                        List.of(new ToolResultContentBlock.Text("kaboom")),
                                        true)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("bash approval unexpectedly failed: kaboom");
    }

    @Test
    void bash_permission_prompt_denied_returns_denied_text() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:bash_permission_prompt_denied"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_bash_prompt_deny",
                                        List.of(new ToolResultContentBlock.Text("denied")),
                                        true)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("bash denied as expected: denied");
    }

    @Test
    void plugin_tool_roundtrip_first_iteration_emits_plugin_echo() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("plugin_tool_roundtrip", true));
        assertThat(response.body())
                .contains("\"id\":\"toolu_plugin_echo\"")
                .contains("\"name\":\"plugin_echo\"")
                .contains("hello from plugin parity");
    }

    @Test
    void plugin_tool_roundtrip_second_iteration_extracts_input_message() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:plugin_tool_roundtrip"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_plugin_echo",
                                        List.of(new ToolResultContentBlock.Text(
                                                "{\"input\":{\"message\":\"hi from plugin\"}}")),
                                        false)))))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.body()).contains("plugin tool completed: hi from plugin");
    }

    @Test
    void auto_compact_triggered_emits_large_input_token_usage() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("auto_compact_triggered", true));
        assertThat(response.body()).contains("\"input_tokens\":50000").contains("auto compact parity complete.");
    }

    @Test
    void token_cost_reporting_emits_known_token_counts() throws Exception {
        HttpResponse<String> response = post_messages(scenario_request("token_cost_reporting", true));
        assertThat(response.body())
                .contains("\"input_tokens\":1000")
                .contains("\"output_tokens\":500")
                .contains("token cost reporting parity complete.");
    }

    @Test
    void captured_requests_tracks_each_inbound_message() throws Exception {
        post_messages(scenario_request("streaming_text", true));
        post_messages(scenario_request("token_cost_reporting", false));
        List<CapturedRequest> captured = service.captured_requests();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).path()).isEqualTo("/v1/messages");
        assertThat(captured.get(0).method()).isEqualTo("POST");
        assertThat(captured.get(0).stream()).isTrue();
        assertThat(captured.get(0).scenario()).contains(Scenario.STREAMING_TEXT);
        assertThat(captured.get(1).stream()).isFalse();
        assertThat(captured.get(1).scenario()).contains(Scenario.TOKEN_COST_REPORTING);
    }

    @Test
    void count_tokens_endpoint_returns_static_payload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(service.base_url() + "/v1/messages/count_tokens"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"input_tokens\":42");
        List<CapturedRequest> captured = service.captured_requests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).path()).isEqualTo("/v1/messages/count_tokens");
    }

    @Test
    void missing_scenario_marker_returns_http_400() throws Exception {
        MessageRequest request = MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(true)
                .messages(List.of(InputMessage.user_text("no marker here")))
                .build();
        HttpResponse<String> response = post_messages(request);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("missing parity scenario");
    }

    @Test
    void close_stops_the_server_and_releases_the_port() throws Exception {
        post_messages(scenario_request("streaming_text", false));
        service.close();
        service = null;
    }

    // --- helpers --------------------------------------------------------------

    private MessageRequest scenario_request(String scenario_name, boolean stream) {
        return MessageRequest.builder().model("claude-sonnet-4-6").max_tokens(64).stream(stream)
                .messages(List.of(InputMessage.user_text("PARITY_SCENARIO:" + scenario_name)))
                .build();
    }

    private HttpResponse<String> post_messages(MessageRequest request) throws Exception {
        String body = mapper.writeValueAsString(request);
        HttpRequest http_request = HttpRequest.newBuilder()
                .uri(URI.create(service.base_url() + "/v1/messages"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(http_request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
