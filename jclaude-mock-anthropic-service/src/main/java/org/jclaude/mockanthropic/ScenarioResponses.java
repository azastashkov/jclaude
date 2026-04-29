package org.jclaude.mockanthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.ToolResultContentBlock;

/**
 * Builds streaming-SSE bodies and non-streaming JSON bodies for each {@link Scenario}. Output is
 * byte-equivalent to the upstream Rust mock-anthropic-service implementation.
 */
final class ScenarioResponses {

    static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final ObjectMapper mapper;
    private final JsonNodeFactory nodes;

    ScenarioResponses(ObjectMapper mapper) {
        this.mapper = mapper;
        this.nodes = JsonNodeFactory.instance;
    }

    String build_stream_body(MessageRequest request, Scenario scenario) {
        return switch (scenario) {
            case STREAMING_TEXT -> streaming_text_sse();
            case READ_FILE_ROUNDTRIP -> latest_tool_result(request)
                    .map(tr -> final_text_sse("read_file roundtrip complete: " + extract_read_content(tr.text)))
                    .orElseGet(() ->
                            tool_use_sse("toolu_read_fixture", "read_file", List.of("{\"path\":\"fixture.txt\"}")));
            case GREP_CHUNK_ASSEMBLY -> latest_tool_result(request)
                    .map(tr -> final_text_sse("grep_search matched " + extract_num_matches(tr.text) + " occurrences"))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_grep_fixture",
                            "grep_search",
                            List.of(
                                    "{\"pattern\":\"par",
                                    "ity\",\"path\":\"fixture.txt\"",
                                    ",\"output_mode\":\"count\"}")));
            case WRITE_FILE_ALLOWED -> latest_tool_result(request)
                    .map(tr -> final_text_sse("write_file succeeded: " + extract_file_path(tr.text)))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_write_allowed",
                            "write_file",
                            List.of("{\"path\":\"generated/output.txt\","
                                    + "\"content\":\"created by mock service\\n\"}")));
            case WRITE_FILE_DENIED -> latest_tool_result(request)
                    .map(tr -> final_text_sse("write_file denied as expected: " + tr.text))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_write_denied",
                            "write_file",
                            List.of("{\"path\":\"generated/denied.txt\"," + "\"content\":\"should not exist\\n\"}")));
            case MULTI_TOOL_TURN_ROUNDTRIP -> {
                Map<String, ToolResult> by_name = tool_results_by_name(request);
                ToolResult read_result = by_name.get("read_file");
                ToolResult grep_result = by_name.get("grep_search");
                if (read_result != null && grep_result != null) {
                    yield final_text_sse("multi-tool roundtrip complete: "
                            + extract_read_content(read_result.text)
                            + " / "
                            + extract_num_matches(grep_result.text)
                            + " occurrences");
                }
                yield tool_uses_sse(List.of(
                        new ToolUseSse("toolu_multi_read", "read_file", List.of("{\"path\":\"fixture.txt\"}")),
                        new ToolUseSse(
                                "toolu_multi_grep",
                                "grep_search",
                                List.of(
                                        "{\"pattern\":\"par",
                                        "ity\",\"path\":\"fixture.txt\"",
                                        ",\"output_mode\":\"count\"}"))));
            }
            case BASH_STDOUT_ROUNDTRIP -> latest_tool_result(request)
                    .map(tr -> final_text_sse("bash completed: " + extract_bash_stdout(tr.text)))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_bash_stdout",
                            "bash",
                            List.of("{\"command\":\"printf 'alpha from bash'\",\"timeout\":1000}")));
            case BASH_PERMISSION_PROMPT_APPROVED -> latest_tool_result(request)
                    .map(tr -> tr.is_error
                            ? final_text_sse("bash approval unexpectedly failed: " + tr.text)
                            : final_text_sse("bash approved and executed: " + extract_bash_stdout(tr.text)))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_bash_prompt_allow",
                            "bash",
                            List.of("{\"command\":\"printf 'approved via prompt'\",\"timeout\":1000}")));
            case BASH_PERMISSION_PROMPT_DENIED -> latest_tool_result(request)
                    .map(tr -> final_text_sse("bash denied as expected: " + tr.text))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_bash_prompt_deny",
                            "bash",
                            List.of("{\"command\":\"printf 'should not run'\",\"timeout\":1000}")));
            case PLUGIN_TOOL_ROUNDTRIP -> latest_tool_result(request)
                    .map(tr -> final_text_sse("plugin tool completed: " + extract_plugin_message(tr.text)))
                    .orElseGet(() -> tool_use_sse(
                            "toolu_plugin_echo", "plugin_echo", List.of("{\"message\":\"hello from plugin parity\"}")));
            case AUTO_COMPACT_TRIGGERED -> final_text_sse_with_usage("auto compact parity complete.", 50_000, 200);
            case TOKEN_COST_REPORTING -> final_text_sse_with_usage("token cost reporting parity complete.", 1_000, 500);
        };
    }

    /** Build the non-streaming JSON body. */
    String build_message_response(MessageRequest request, Scenario scenario) {
        ObjectNode response =
                switch (scenario) {
                    case STREAMING_TEXT -> text_message_response(
                            "msg_streaming_text", "Mock streaming says hello from the parity harness.");
                    case READ_FILE_ROUNDTRIP -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_read_file_final",
                                    "read_file roundtrip complete: " + extract_read_content(tr.text)))
                            .orElseGet(() -> tool_message_response(
                                    "msg_read_file_tool",
                                    "toolu_read_fixture",
                                    "read_file",
                                    json_object(Map.of("path", "fixture.txt"))));
                    case GREP_CHUNK_ASSEMBLY -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_grep_final",
                                    "grep_search matched " + extract_num_matches(tr.text) + " occurrences"))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("pattern", "parity");
                                input.put("path", "fixture.txt");
                                input.put("output_mode", "count");
                                return tool_message_response(
                                        "msg_grep_tool", "toolu_grep_fixture", "grep_search", input);
                            });
                    case WRITE_FILE_ALLOWED -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_write_allowed_final", "write_file succeeded: " + extract_file_path(tr.text)))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("path", "generated/output.txt");
                                input.put("content", "created by mock service\n");
                                return tool_message_response(
                                        "msg_write_allowed_tool", "toolu_write_allowed", "write_file", input);
                            });
                    case WRITE_FILE_DENIED -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_write_denied_final", "write_file denied as expected: " + tr.text))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("path", "generated/denied.txt");
                                input.put("content", "should not exist\n");
                                return tool_message_response(
                                        "msg_write_denied_tool", "toolu_write_denied", "write_file", input);
                            });
                    case MULTI_TOOL_TURN_ROUNDTRIP -> {
                        Map<String, ToolResult> by_name = tool_results_by_name(request);
                        ToolResult read_result = by_name.get("read_file");
                        ToolResult grep_result = by_name.get("grep_search");
                        if (read_result != null && grep_result != null) {
                            yield text_message_response(
                                    "msg_multi_tool_final",
                                    "multi-tool roundtrip complete: "
                                            + extract_read_content(read_result.text)
                                            + " / "
                                            + extract_num_matches(grep_result.text)
                                            + " occurrences");
                        }
                        ObjectNode read_input = nodes.objectNode();
                        read_input.put("path", "fixture.txt");
                        ObjectNode grep_input = nodes.objectNode();
                        grep_input.put("pattern", "parity");
                        grep_input.put("path", "fixture.txt");
                        grep_input.put("output_mode", "count");
                        yield tool_message_response_many(
                                "msg_multi_tool_start",
                                List.of(
                                        new ToolUseMessage("toolu_multi_read", "read_file", read_input),
                                        new ToolUseMessage("toolu_multi_grep", "grep_search", grep_input)));
                    }
                    case BASH_STDOUT_ROUNDTRIP -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_bash_stdout_final", "bash completed: " + extract_bash_stdout(tr.text)))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("command", "printf 'alpha from bash'");
                                input.put("timeout", 1000);
                                return tool_message_response(
                                        "msg_bash_stdout_tool", "toolu_bash_stdout", "bash", input);
                            });
                    case BASH_PERMISSION_PROMPT_APPROVED -> latest_tool_result(request)
                            .map(tr -> tr.is_error
                                    ? text_message_response(
                                            "msg_bash_prompt_allow_error",
                                            "bash approval unexpectedly failed: " + tr.text)
                                    : text_message_response(
                                            "msg_bash_prompt_allow_final",
                                            "bash approved and executed: " + extract_bash_stdout(tr.text)))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("command", "printf 'approved via prompt'");
                                input.put("timeout", 1000);
                                return tool_message_response(
                                        "msg_bash_prompt_allow_tool", "toolu_bash_prompt_allow", "bash", input);
                            });
                    case BASH_PERMISSION_PROMPT_DENIED -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_bash_prompt_deny_final", "bash denied as expected: " + tr.text))
                            .orElseGet(() -> {
                                ObjectNode input = nodes.objectNode();
                                input.put("command", "printf 'should not run'");
                                input.put("timeout", 1000);
                                return tool_message_response(
                                        "msg_bash_prompt_deny_tool", "toolu_bash_prompt_deny", "bash", input);
                            });
                    case PLUGIN_TOOL_ROUNDTRIP -> latest_tool_result(request)
                            .map(tr -> text_message_response(
                                    "msg_plugin_tool_final",
                                    "plugin tool completed: " + extract_plugin_message(tr.text)))
                            .orElseGet(() -> tool_message_response(
                                    "msg_plugin_tool_start",
                                    "toolu_plugin_echo",
                                    "plugin_echo",
                                    json_object(Map.of("message", "hello from plugin parity"))));
                    case AUTO_COMPACT_TRIGGERED -> text_message_response_with_usage(
                            "msg_auto_compact_triggered", "auto compact parity complete.", 50_000, 200);
                    case TOKEN_COST_REPORTING -> text_message_response_with_usage(
                            "msg_token_cost_reporting", "token cost reporting parity complete.", 1_000, 500);
                };
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("message response should serialize", e);
        }
    }

    static String request_id_for(Scenario scenario) {
        return switch (scenario) {
            case STREAMING_TEXT -> "req_streaming_text";
            case READ_FILE_ROUNDTRIP -> "req_read_file_roundtrip";
            case GREP_CHUNK_ASSEMBLY -> "req_grep_chunk_assembly";
            case WRITE_FILE_ALLOWED -> "req_write_file_allowed";
            case WRITE_FILE_DENIED -> "req_write_file_denied";
            case MULTI_TOOL_TURN_ROUNDTRIP -> "req_multi_tool_turn_roundtrip";
            case BASH_STDOUT_ROUNDTRIP -> "req_bash_stdout_roundtrip";
            case BASH_PERMISSION_PROMPT_APPROVED -> "req_bash_permission_prompt_approved";
            case BASH_PERMISSION_PROMPT_DENIED -> "req_bash_permission_prompt_denied";
            case PLUGIN_TOOL_ROUNDTRIP -> "req_plugin_tool_roundtrip";
            case AUTO_COMPACT_TRIGGERED -> "req_auto_compact_triggered";
            case TOKEN_COST_REPORTING -> "req_token_cost_reporting";
        };
    }

    // --- streaming helpers ----------------------------------------------------

    private String streaming_text_sse() {
        StringBuilder body = new StringBuilder();
        ObjectNode message_start = nodes.objectNode();
        message_start.put("type", "message_start");
        ObjectNode m = message_start.putObject("message");
        m.put("id", "msg_streaming_text");
        m.put("type", "message");
        m.put("role", "assistant");
        m.set("content", nodes.arrayNode());
        m.put("model", DEFAULT_MODEL);
        m.putNull("stop_reason");
        m.putNull("stop_sequence");
        m.set("usage", usage_json(11, 0));
        append_sse(body, "message_start", message_start);

        append_sse(body, "content_block_start", content_block_start_text(0));
        append_sse(body, "content_block_delta", text_delta(0, "Mock streaming "));
        append_sse(body, "content_block_delta", text_delta(0, "says hello from the parity harness."));
        append_sse(body, "content_block_stop", content_block_stop(0));

        ObjectNode message_delta = nodes.objectNode();
        message_delta.put("type", "message_delta");
        ObjectNode delta = message_delta.putObject("delta");
        delta.put("stop_reason", "end_turn");
        delta.putNull("stop_sequence");
        message_delta.set("usage", usage_json(11, 8));
        append_sse(body, "message_delta", message_delta);

        ObjectNode message_stop = nodes.objectNode();
        message_stop.put("type", "message_stop");
        append_sse(body, "message_stop", message_stop);
        return body.toString();
    }

    private String tool_use_sse(String tool_id, String tool_name, List<String> chunks) {
        return tool_uses_sse(List.of(new ToolUseSse(tool_id, tool_name, chunks)));
    }

    private String tool_uses_sse(List<ToolUseSse> tool_uses) {
        StringBuilder body = new StringBuilder();
        String message_id = tool_uses.isEmpty() ? "msg_tool_use" : "msg_" + tool_uses.get(0).id;

        ObjectNode message_start = nodes.objectNode();
        message_start.put("type", "message_start");
        ObjectNode m = message_start.putObject("message");
        m.put("id", message_id);
        m.put("type", "message");
        m.put("role", "assistant");
        m.set("content", nodes.arrayNode());
        m.put("model", DEFAULT_MODEL);
        m.putNull("stop_reason");
        m.putNull("stop_sequence");
        m.set("usage", usage_json(12, 0));
        append_sse(body, "message_start", message_start);

        for (int idx = 0; idx < tool_uses.size(); idx++) {
            ToolUseSse t = tool_uses.get(idx);
            ObjectNode start = nodes.objectNode();
            start.put("type", "content_block_start");
            start.put("index", idx);
            ObjectNode block = start.putObject("content_block");
            block.put("type", "tool_use");
            block.put("id", t.id);
            block.put("name", t.name);
            block.set("input", nodes.objectNode());
            append_sse(body, "content_block_start", start);

            for (String chunk : t.chunks) {
                ObjectNode cd = nodes.objectNode();
                cd.put("type", "content_block_delta");
                cd.put("index", idx);
                ObjectNode d = cd.putObject("delta");
                d.put("type", "input_json_delta");
                d.put("partial_json", chunk);
                append_sse(body, "content_block_delta", cd);
            }

            append_sse(body, "content_block_stop", content_block_stop(idx));
        }

        ObjectNode message_delta = nodes.objectNode();
        message_delta.put("type", "message_delta");
        ObjectNode delta = message_delta.putObject("delta");
        delta.put("stop_reason", "tool_use");
        delta.putNull("stop_sequence");
        message_delta.set("usage", usage_json(12, 4));
        append_sse(body, "message_delta", message_delta);

        ObjectNode message_stop = nodes.objectNode();
        message_stop.put("type", "message_stop");
        append_sse(body, "message_stop", message_stop);
        return body.toString();
    }

    private String final_text_sse(String text) {
        StringBuilder body = new StringBuilder();
        ObjectNode message_start = nodes.objectNode();
        message_start.put("type", "message_start");
        ObjectNode m = message_start.putObject("message");
        m.put("id", unique_message_id());
        m.put("type", "message");
        m.put("role", "assistant");
        m.set("content", nodes.arrayNode());
        m.put("model", DEFAULT_MODEL);
        m.putNull("stop_reason");
        m.putNull("stop_sequence");
        m.set("usage", usage_json(14, 0));
        append_sse(body, "message_start", message_start);

        append_sse(body, "content_block_start", content_block_start_text(0));
        append_sse(body, "content_block_delta", text_delta(0, text));
        append_sse(body, "content_block_stop", content_block_stop(0));

        ObjectNode message_delta = nodes.objectNode();
        message_delta.put("type", "message_delta");
        ObjectNode delta = message_delta.putObject("delta");
        delta.put("stop_reason", "end_turn");
        delta.putNull("stop_sequence");
        message_delta.set("usage", usage_json(14, 7));
        append_sse(body, "message_delta", message_delta);

        ObjectNode message_stop = nodes.objectNode();
        message_stop.put("type", "message_stop");
        append_sse(body, "message_stop", message_stop);
        return body.toString();
    }

    private String final_text_sse_with_usage(String text, long input_tokens, long output_tokens) {
        StringBuilder body = new StringBuilder();
        ObjectNode message_start = nodes.objectNode();
        message_start.put("type", "message_start");
        ObjectNode m = message_start.putObject("message");
        m.put("id", unique_message_id());
        m.put("type", "message");
        m.put("role", "assistant");
        m.set("content", nodes.arrayNode());
        m.put("model", DEFAULT_MODEL);
        m.putNull("stop_reason");
        m.putNull("stop_sequence");
        ObjectNode start_usage = nodes.objectNode();
        start_usage.put("input_tokens", input_tokens);
        start_usage.put("cache_creation_input_tokens", 0);
        start_usage.put("cache_read_input_tokens", 0);
        start_usage.put("output_tokens", 0);
        m.set("usage", start_usage);
        append_sse(body, "message_start", message_start);

        append_sse(body, "content_block_start", content_block_start_text(0));
        append_sse(body, "content_block_delta", text_delta(0, text));
        append_sse(body, "content_block_stop", content_block_stop(0));

        ObjectNode message_delta = nodes.objectNode();
        message_delta.put("type", "message_delta");
        ObjectNode delta = message_delta.putObject("delta");
        delta.put("stop_reason", "end_turn");
        delta.putNull("stop_sequence");
        ObjectNode delta_usage = nodes.objectNode();
        delta_usage.put("input_tokens", input_tokens);
        delta_usage.put("cache_creation_input_tokens", 0);
        delta_usage.put("cache_read_input_tokens", 0);
        delta_usage.put("output_tokens", output_tokens);
        message_delta.set("usage", delta_usage);
        append_sse(body, "message_delta", message_delta);

        ObjectNode message_stop = nodes.objectNode();
        message_stop.put("type", "message_stop");
        append_sse(body, "message_stop", message_stop);
        return body.toString();
    }

    // --- non-streaming helpers ------------------------------------------------

    private ObjectNode text_message_response(String id, String text) {
        return text_message_response(id, text, 10, 6);
    }

    private ObjectNode text_message_response_with_usage(String id, String text, long input_tokens, long output_tokens) {
        return text_message_response(id, text, input_tokens, output_tokens);
    }

    private ObjectNode text_message_response(String id, String text, long input_tokens, long output_tokens) {
        ObjectNode response = nodes.objectNode();
        response.put("id", id);
        response.put("type", "message");
        response.put("role", "assistant");
        ArrayNode content = response.putArray("content");
        ObjectNode text_block = content.addObject();
        text_block.put("type", "text");
        text_block.put("text", text);
        response.put("model", DEFAULT_MODEL);
        response.put("stop_reason", "end_turn");
        response.putNull("stop_sequence");
        ObjectNode usage = response.putObject("usage");
        usage.put("input_tokens", input_tokens);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        usage.put("output_tokens", output_tokens);
        return response;
    }

    private ObjectNode tool_message_response(String id, String tool_id, String tool_name, JsonNode input) {
        return tool_message_response_many(id, List.of(new ToolUseMessage(tool_id, tool_name, input)));
    }

    private ObjectNode tool_message_response_many(String id, List<ToolUseMessage> tool_uses) {
        ObjectNode response = nodes.objectNode();
        response.put("id", id);
        response.put("type", "message");
        response.put("role", "assistant");
        ArrayNode content = response.putArray("content");
        for (ToolUseMessage t : tool_uses) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", t.id);
            block.put("name", t.name);
            block.set("input", t.input);
        }
        response.put("model", DEFAULT_MODEL);
        response.put("stop_reason", "tool_use");
        response.putNull("stop_sequence");
        ObjectNode usage = response.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        usage.put("output_tokens", 3);
        return response;
    }

    // --- node helpers ---------------------------------------------------------

    private ObjectNode usage_json(long input_tokens, long output_tokens) {
        ObjectNode usage = nodes.objectNode();
        usage.put("input_tokens", input_tokens);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        usage.put("output_tokens", output_tokens);
        return usage;
    }

    private ObjectNode content_block_start_text(int index) {
        ObjectNode obj = nodes.objectNode();
        obj.put("type", "content_block_start");
        obj.put("index", index);
        ObjectNode cb = obj.putObject("content_block");
        cb.put("type", "text");
        cb.put("text", "");
        return obj;
    }

    private ObjectNode text_delta(int index, String text) {
        ObjectNode obj = nodes.objectNode();
        obj.put("type", "content_block_delta");
        obj.put("index", index);
        ObjectNode delta = obj.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", text);
        return obj;
    }

    private ObjectNode content_block_stop(int index) {
        ObjectNode obj = nodes.objectNode();
        obj.put("type", "content_block_stop");
        obj.put("index", index);
        return obj;
    }

    private ObjectNode json_object(Map<String, ?> entries) {
        ObjectNode obj = nodes.objectNode();
        for (Map.Entry<String, ?> e : entries.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                obj.put(e.getKey(), s);
            } else if (v instanceof Long l) {
                obj.put(e.getKey(), l);
            } else if (v instanceof Integer i) {
                obj.put(e.getKey(), i);
            } else if (v instanceof Double d) {
                obj.put(e.getKey(), d);
            } else if (v instanceof Boolean b) {
                obj.put(e.getKey(), b);
            } else if (v instanceof JsonNode n) {
                obj.set(e.getKey(), n);
            } else {
                throw new IllegalArgumentException("unsupported JSON value: " + v);
            }
        }
        return obj;
    }

    private void append_sse(StringBuilder buffer, String event, JsonNode payload) {
        buffer.append("event: ").append(event).append('\n');
        try {
            buffer.append("data: ").append(mapper.writeValueAsString(payload)).append('\n');
        } catch (Exception ex) {
            throw new IllegalStateException("payload write should succeed", ex);
        }
        buffer.append('\n');
    }

    private static String unique_message_id() {
        long nanos =
                Instant.now().getEpochSecond() * 1_000_000_000L + Instant.now().getNano();
        return "msg_" + nanos;
    }

    // --- request inspection ---------------------------------------------------

    record ToolResult(String text, boolean is_error) {}

    private static Optional<ToolResult> latest_tool_result(MessageRequest request) {
        if (request == null || request.messages() == null) {
            return Optional.empty();
        }
        var messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            InputMessage message = messages.get(i);
            if (message == null || message.content() == null) {
                continue;
            }
            var content = message.content();
            for (int j = content.size() - 1; j >= 0; j--) {
                InputContentBlock block = content.get(j);
                if (block instanceof InputContentBlock.ToolResult tr) {
                    return Optional.of(new ToolResult(flatten_tool_result_content(tr.content()), tr.is_error()));
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, ToolResult> tool_results_by_name(MessageRequest request) {
        Map<String, String> tool_names_by_id = new HashMap<>();
        if (request == null || request.messages() == null) {
            return Map.of();
        }
        for (InputMessage message : request.messages()) {
            if (message == null || message.content() == null) continue;
            for (InputContentBlock block : message.content()) {
                if (block instanceof InputContentBlock.ToolUse tu) {
                    tool_names_by_id.put(tu.id(), tu.name());
                }
            }
        }
        Map<String, ToolResult> results = new HashMap<>();
        var messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            InputMessage message = messages.get(i);
            if (message == null || message.content() == null) continue;
            var content = message.content();
            for (int j = content.size() - 1; j >= 0; j--) {
                InputContentBlock block = content.get(j);
                if (block instanceof InputContentBlock.ToolResult tr) {
                    String tool_name = tool_names_by_id.getOrDefault(tr.tool_use_id(), tr.tool_use_id());
                    results.computeIfAbsent(
                            tool_name, k -> new ToolResult(flatten_tool_result_content(tr.content()), tr.is_error()));
                }
            }
        }
        return results;
    }

    private static String flatten_tool_result_content(List<ToolResultContentBlock> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            ToolResultContentBlock block = content.get(i);
            if (i > 0) sb.append('\n');
            switch (block) {
                case ToolResultContentBlock.Text t -> sb.append(t.text());
                case ToolResultContentBlock.Json j -> sb.append(j.value().toString());
            }
        }
        return sb.toString();
    }

    String extract_read_content(String tool_output) {
        try {
            JsonNode value = mapper.readTree(tool_output);
            JsonNode file = value.get("file");
            if (file != null) {
                JsonNode content = file.get("content");
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return tool_output == null ? "" : tool_output.trim();
    }

    long extract_num_matches(String tool_output) {
        try {
            JsonNode value = mapper.readTree(tool_output);
            JsonNode num = value.get("numMatches");
            if (num == null) {
                num = value.get("num_matches");
            }
            if (num != null && num.canConvertToLong()) {
                long v = num.asLong();
                return v < 0 ? 0 : v;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return 0;
    }

    String extract_file_path(String tool_output) {
        try {
            JsonNode value = mapper.readTree(tool_output);
            JsonNode path = value.get("filePath");
            if (path == null) {
                path = value.get("file_path");
            }
            if (path != null && path.isTextual()) {
                return path.asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return tool_output == null ? "" : tool_output.trim();
    }

    String extract_bash_stdout(String tool_output) {
        try {
            JsonNode value = mapper.readTree(tool_output);
            JsonNode stdout = value.get("stdout");
            if (stdout != null && stdout.isTextual()) {
                return stdout.asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return tool_output == null ? "" : tool_output.trim();
    }

    String extract_plugin_message(String tool_output) {
        try {
            JsonNode value = mapper.readTree(tool_output);
            JsonNode input = value.get("input");
            if (input != null) {
                JsonNode message = input.get("message");
                if (message != null && message.isTextual()) {
                    return message.asText();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return tool_output == null ? "" : tool_output.trim();
    }

    private record ToolUseSse(String id, String name, List<String> chunks) {}

    private record ToolUseMessage(String id, String name, JsonNode input) {}
}
