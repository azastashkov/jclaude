package org.jclaude.cli.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.providers.OpenAiCompatClient;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.ToolDefinition;
import org.jclaude.api.types.ToolResultContentBlock;
import org.jclaude.api.types.Usage;
import org.jclaude.runtime.conversation.ApiClient;
import org.jclaude.runtime.conversation.ApiRequest;
import org.jclaude.runtime.conversation.AssistantEvent;
import org.jclaude.runtime.conversation.ProgressListener;
import org.jclaude.runtime.conversation.RuntimeError;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.usage.TokenUsage;
import org.jclaude.tools.ToolSpec;

/**
 * {@link ApiClient} adapter that drives an {@link OpenAiCompatClient} and
 * translates the streamed {@link StreamEvent}s into the runtime-facing
 * {@link AssistantEvent} sequence.
 */
public final class OpenAiRuntimeApiClient implements ApiClient {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final OpenAiCompatClient client;
    private final String model;
    private final long max_tokens;
    private final List<ToolDefinition> tools;

    public OpenAiRuntimeApiClient(OpenAiCompatClient client, String model, long max_tokens, List<ToolSpec> tool_specs) {
        this.client = client;
        this.model = model;
        this.max_tokens = max_tokens;
        this.tools = build_tool_definitions(tool_specs);
    }

    @Override
    public List<AssistantEvent> stream(ApiRequest runtime_request) {
        return stream(runtime_request, ProgressListener.NO_OP);
    }

    @Override
    public List<AssistantEvent> stream(ApiRequest runtime_request, ProgressListener listener) {
        MessageRequest wire_request = build_message_request(runtime_request);
        try (OpenAiCompatClient.StreamingResponse response = client.stream_message(wire_request)) {
            return translate_stream(response, listener);
        } catch (RuntimeException error) {
            // Surface every transport / API failure as RuntimeError so the
            // ConversationRuntime can propagate it.
            if (error instanceof RuntimeError already) {
                throw already;
            }
            throw new RuntimeError(
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
        }
    }

    private MessageRequest build_message_request(ApiRequest runtime_request) {
        String system =
                runtime_request.system_prompt().isEmpty() ? null : String.join("\n\n", runtime_request.system_prompt());
        List<InputMessage> messages = new ArrayList<>(runtime_request.messages().size());
        for (ConversationMessage message : runtime_request.messages()) {
            InputMessage translated = translate_message(message);
            if (translated != null) {
                messages.add(translated);
            }
        }
        MessageRequest.Builder builder =
                MessageRequest.builder().model(model).max_tokens(max_tokens).messages(messages).stream(true);
        if (system != null) {
            builder.system(system);
        }
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }
        return builder.build();
    }

    private static InputMessage translate_message(ConversationMessage message) {
        List<InputContentBlock> content = new ArrayList<>(message.blocks().size());
        for (ContentBlock block : message.blocks()) {
            InputContentBlock translated = translate_block(block);
            if (translated != null) {
                content.add(translated);
            }
        }
        if (content.isEmpty()) {
            return null;
        }
        String role = wire_role(message.role());
        return new InputMessage(role, content);
    }

    private static String wire_role(MessageRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "user"; // Tool results are sent as user-role tool_result blocks.
            case SYSTEM -> "user"; // ConversationRuntime puts compaction continuations in SYSTEM messages.
        };
    }

    private static InputContentBlock translate_block(ContentBlock block) {
        if (block instanceof ContentBlock.Text text) {
            return new InputContentBlock.Text(text.text());
        }
        if (block instanceof ContentBlock.ToolUse use) {
            JsonNode parsed = parse_input_json(use.input());
            return new InputContentBlock.ToolUse(use.id(), use.name(), parsed);
        }
        if (block instanceof ContentBlock.ToolResult result) {
            return new InputContentBlock.ToolResult(
                    result.tool_use_id(), List.of(new ToolResultContentBlock.Text(result.output())), result.is_error());
        }
        return null;
    }

    private static JsonNode parse_input_json(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException ignored) {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.put("raw", raw);
            return wrapper;
        }
    }

    /** Translate the OpenAI-shape stream into the runtime-facing event list. */
    static List<AssistantEvent> translate_stream(Iterable<StreamEvent> events) {
        return translate_stream(events, ProgressListener.NO_OP);
    }

    /**
     * Listener-aware variant of {@link #translate_stream(Iterable)}. Emits incremental progress
     * signals to {@code listener} as the SSE chunks arrive, then post-processes the buffered
     * event list (including the Hermes-XML rewrite) the same way as the non-listener variant.
     */
    static List<AssistantEvent> translate_stream(Iterable<StreamEvent> events, ProgressListener listener) {
        List<AssistantEvent> out = new ArrayList<>();
        // Track the per-block-index state so we can buffer tool-use input JSON
        // chunks and emit one AssistantEvent.ToolUse on content_block_stop.
        Map<Integer, ToolUseAccumulator> tool_uses = new HashMap<>();
        Usage final_usage = null;
        boolean message_stop_seen = false;

        // Mid-stream Hermes-XML scan state. qwen3-coder + similar models emit tool calls inside
        // assistant text rather than as structured tool_calls, so we cannot rely on
        // ContentBlockStart to know which tool the model is calling. Instead, we accumulate text
        // chunks and scan for the closing `>` of `<function=NAME>` — the first instant the tool
        // name is decoded. The non-listener path doesn't pay for this scan.
        StringBuilder text_scan_buffer = new StringBuilder();
        int hermes_scan_cursor = 0;

        for (StreamEvent event : events) {
            if (event instanceof StreamEvent.ContentBlockStart start) {
                if (start.content_block() instanceof OutputContentBlock.ToolUse use) {
                    ToolUseAccumulator acc = new ToolUseAccumulator(use.id(), use.name());
                    if (use.input() != null
                            && use.input().isObject()
                            && use.input().size() > 0) {
                        // Some upstream streams emit the full input on the start event; capture it.
                        try {
                            acc.buffer.append(MAPPER.writeValueAsString(use.input()));
                        } catch (JsonProcessingException ignored) {
                            // best effort
                        }
                    }
                    tool_uses.put(start.index(), acc);
                    listener.on_tool_starting(use.name());
                }
            } else if (event instanceof StreamEvent.ContentBlockDelta delta) {
                if (delta.delta() instanceof BlockDelta.TextDelta td) {
                    out.add(new AssistantEvent.TextDelta(td.text()));
                    listener.on_text_delta_received(td.text().length());
                    text_scan_buffer.append(td.text());
                    hermes_scan_cursor =
                            scan_for_hermes_tool_starts(text_scan_buffer, hermes_scan_cursor, listener);
                } else if (delta.delta() instanceof BlockDelta.InputJsonDelta json_delta) {
                    ToolUseAccumulator acc = tool_uses.get(delta.index());
                    if (acc != null) {
                        acc.buffer.append(json_delta.partial_json());
                    }
                }
            } else if (event instanceof StreamEvent.ContentBlockStop stop) {
                ToolUseAccumulator acc = tool_uses.remove(stop.index());
                if (acc != null) {
                    String input_json = acc.buffer.length() == 0 ? "{}" : acc.buffer.toString();
                    out.add(new AssistantEvent.ToolUse(acc.id, acc.name, input_json));
                }
            } else if (event instanceof StreamEvent.MessageDelta md) {
                if (md.usage() != null) {
                    final_usage = md.usage();
                }
            } else if (event instanceof StreamEvent.MessageStop) {
                message_stop_seen = true;
            }
            // MessageStart and Ping are intentionally ignored.
        }
        // Drain any tool uses that did not receive an explicit stop event (defensive).
        for (ToolUseAccumulator acc : tool_uses.values()) {
            String input_json = acc.buffer.length() == 0 ? "{}" : acc.buffer.toString();
            out.add(new AssistantEvent.ToolUse(acc.id, acc.name, input_json));
        }
        if (final_usage != null) {
            TokenUsage usage = new TokenUsage(
                    final_usage.input_tokens(),
                    final_usage.output_tokens(),
                    final_usage.cache_creation_input_tokens(),
                    final_usage.cache_read_input_tokens());
            out.add(new AssistantEvent.Usage(usage));
        }
        if (!message_stop_seen) {
            // Synthesize a stop so ConversationRuntime.build_assistant_message
            // does not raise.
            out.add(AssistantEvent.MessageStop.INSTANCE);
        } else {
            out.add(AssistantEvent.MessageStop.INSTANCE);
        }
        return rewrite_hermes_style_tool_calls(out);
    }

    // Mid-stream Hermes-XML tool-name detector. Matches `<function=NAME>` where the closing `>`
    // has actually arrived — partial chunks like `<function=read_fi` won't trigger early.
    private static final Pattern HERMES_FUNCTION_OPEN = Pattern.compile("<function=([\\w_.\\-]+)>");

    /**
     * Scan {@code buffer} from {@code cursor} for any newly-arrived {@code <function=NAME>} open
     * tag, fire {@link ProgressListener#on_tool_starting(String)} for each, and return the
     * advanced cursor position. Idempotent: calling repeatedly with the same cursor never
     * re-fires for a tag that has already been reported.
     */
    static int scan_for_hermes_tool_starts(StringBuilder buffer, int cursor, ProgressListener listener) {
        if (cursor >= buffer.length()) {
            return cursor;
        }
        Matcher matcher = HERMES_FUNCTION_OPEN.matcher(buffer);
        int next_cursor = cursor;
        if (matcher.region(cursor, buffer.length()).find()) {
            do {
                listener.on_tool_starting(matcher.group(1));
                next_cursor = matcher.end();
            } while (matcher.find());
        }
        return next_cursor;
    }

    // qwen3-coder + similar Hermes-template models emit tool calls as XML-shaped
    // text inside the assistant message instead of OpenAI's structured `tool_calls`.
    // Detect that pattern and rewrite the events into proper ToolUse events so the
    // dispatcher can execute them.
    private static final Pattern HERMES_FUNCTION_BLOCK =
            Pattern.compile("<function=([\\w_.\\-]+)>(.*?)</function>", Pattern.DOTALL);
    private static final Pattern HERMES_PARAMETER_BLOCK =
            Pattern.compile("<parameter=([\\w_.\\-]+)>(.*?)</parameter>", Pattern.DOTALL);
    private static final Pattern HERMES_TOOL_CALL_WRAPPER = Pattern.compile("</?tool_call>");

    static List<AssistantEvent> rewrite_hermes_style_tool_calls(List<AssistantEvent> events) {
        // Concatenate all leading TextDelta events to inspect for the pattern.
        StringBuilder text_buffer = new StringBuilder();
        int first_text_index = -1;
        int last_text_index = -1;
        for (int i = 0; i < events.size(); i++) {
            AssistantEvent ev = events.get(i);
            if (ev instanceof AssistantEvent.TextDelta td) {
                if (first_text_index < 0) {
                    first_text_index = i;
                }
                last_text_index = i;
                text_buffer.append(td.text());
            } else if (ev instanceof AssistantEvent.ToolUse) {
                // Already have structured tool calls — don't double-process.
                return events;
            }
        }
        String text = text_buffer.toString();
        if (!text.contains("<function=")) {
            return events;
        }
        Matcher fn = HERMES_FUNCTION_BLOCK.matcher(text);
        List<AssistantEvent.ToolUse> parsed_calls = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        int cursor = 0;
        int call_index = 0;
        while (fn.find()) {
            cleaned.append(text, cursor, fn.start());
            cursor = fn.end();
            String name = fn.group(1);
            String body = fn.group(2);
            ObjectNode args = MAPPER.createObjectNode();
            Matcher param = HERMES_PARAMETER_BLOCK.matcher(body);
            while (param.find()) {
                String key = param.group(1);
                String value = param.group(2).trim();
                args.put(key, value);
            }
            String input_json;
            try {
                input_json = MAPPER.writeValueAsString(args);
            } catch (JsonProcessingException e) {
                input_json = "{}";
            }
            String synthesized_id = "hermes_" + System.nanoTime() + "_" + (call_index++);
            parsed_calls.add(new AssistantEvent.ToolUse(synthesized_id, name, input_json));
        }
        cleaned.append(text, cursor, text.length());
        // Strip leftover <tool_call> / </tool_call> wrappers.
        String cleaned_text = HERMES_TOOL_CALL_WRAPPER
                .matcher(cleaned.toString())
                .replaceAll("")
                .trim();

        // Rebuild the event list: drop the original TextDelta range, insert (a) one
        // TextDelta for any non-empty cleaned text, then (b) all parsed tool uses,
        // before whatever followed (Usage, MessageStop, etc).
        List<AssistantEvent> rebuilt = new ArrayList<>(events.size() + parsed_calls.size());
        for (int i = 0; i < events.size(); i++) {
            if (i >= first_text_index && i <= last_text_index) {
                if (i == first_text_index) {
                    if (!cleaned_text.isEmpty()) {
                        rebuilt.add(new AssistantEvent.TextDelta(cleaned_text));
                    }
                    rebuilt.addAll(parsed_calls);
                }
                continue;
            }
            rebuilt.add(events.get(i));
        }
        return rebuilt;
    }

    private static List<ToolDefinition> build_tool_definitions(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> defs = new ArrayList<>(specs.size());
        for (ToolSpec spec : specs) {
            defs.add(new ToolDefinition(spec.name(), spec.description(), spec.input_schema()));
        }
        return defs;
    }

    private static final class ToolUseAccumulator {
        final String id;
        final String name;
        final StringBuilder buffer = new StringBuilder();

        ToolUseAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
