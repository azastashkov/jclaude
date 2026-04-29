package org.jclaude.cli.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        MessageRequest wire_request = build_message_request(runtime_request);
        try (OpenAiCompatClient.StreamingResponse response = client.stream_message(wire_request)) {
            return translate_stream(response);
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
        List<AssistantEvent> out = new ArrayList<>();
        // Track the per-block-index state so we can buffer tool-use input JSON
        // chunks and emit one AssistantEvent.ToolUse on content_block_stop.
        Map<Integer, ToolUseAccumulator> tool_uses = new HashMap<>();
        Usage final_usage = null;
        boolean message_stop_seen = false;

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
                }
            } else if (event instanceof StreamEvent.ContentBlockDelta delta) {
                if (delta.delta() instanceof BlockDelta.TextDelta td) {
                    out.add(new AssistantEvent.TextDelta(td.text()));
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
        return out;
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
