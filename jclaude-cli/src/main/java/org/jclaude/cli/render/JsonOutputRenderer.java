package org.jclaude.cli.render;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.usage.ModelPricing;
import org.jclaude.runtime.usage.TokenUsage;
import org.jclaude.runtime.usage.UsageCostEstimate;

/**
 * Renders a {@link TurnSummary} as a single JSON object on stdout. Shape:
 *
 * <pre>{@code
 * {
 *   "kind": "result",
 *   "model": "...",
 *   "message": "...",
 *   "iterations": N,
 *   "tool_uses": [{"id", "name", "input"}],
 *   "tool_results": [{"tool_use_id", "tool_name", "output", "is_error"}],
 *   "usage": {...},
 *   "estimated_cost": "$0.0023"
 * }
 * }</pre>
 */
public final class JsonOutputRenderer {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final PrintStream out;

    public JsonOutputRenderer() {
        this(System.out);
    }

    public JsonOutputRenderer(PrintStream out) {
        this.out = out;
    }

    public void render(TurnSummary summary, String model) {
        ObjectNode root = build(summary, model);
        try {
            out.println(MAPPER.writeValueAsString(root));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize JSON output: " + error.getMessage(), error);
        }
        out.flush();
    }

    static ObjectNode build(TurnSummary summary, String model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "result");
        root.put("model", model);
        root.put("message", concatenate_assistant_text(summary));
        root.put("iterations", summary.iterations());
        // Mirror the Rust renderer: emit `auto_compaction` always, as object or null.
        if (summary.auto_compaction().isPresent()) {
            ObjectNode auto = MAPPER.createObjectNode();
            int removed = summary.auto_compaction().get().removed_message_count();
            auto.put("removed_messages", removed);
            auto.put("notice", format_auto_compaction_notice(removed));
            root.set("auto_compaction", auto);
        } else {
            root.putNull("auto_compaction");
        }

        ArrayNode tool_uses = MAPPER.createArrayNode();
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolUse use) {
                    ObjectNode use_node = MAPPER.createObjectNode();
                    use_node.put("id", use.id());
                    use_node.put("name", use.name());
                    use_node.set("input", parse_input_node(use.input()));
                    tool_uses.add(use_node);
                }
            }
        }
        root.set("tool_uses", tool_uses);

        ArrayNode tool_results = MAPPER.createArrayNode();
        for (ConversationMessage message : summary.tool_results()) {
            if (message.role() != MessageRole.TOOL) {
                continue;
            }
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolResult result) {
                    ObjectNode result_node = MAPPER.createObjectNode();
                    result_node.put("tool_use_id", result.tool_use_id());
                    result_node.put("tool_name", result.tool_name());
                    result_node.put("output", result.output());
                    result_node.put("is_error", result.is_error());
                    tool_results.add(result_node);
                }
            }
        }
        root.set("tool_results", tool_results);

        TokenUsage usage = summary.usage();
        ObjectNode usage_node = MAPPER.createObjectNode();
        usage_node.put("input_tokens", usage.input_tokens());
        usage_node.put("output_tokens", usage.output_tokens());
        usage_node.put("cache_creation_input_tokens", usage.cache_creation_input_tokens());
        usage_node.put("cache_read_input_tokens", usage.cache_read_input_tokens());
        root.set("usage", usage_node);

        UsageCostEstimate cost = ModelPricing.pricing_for_model(model)
                .map(usage::estimate_cost_usd_with_pricing)
                .orElseGet(usage::estimate_cost_usd);
        root.put("estimated_cost", UsageCostEstimate.format_usd(cost.total_cost_usd()));
        return root;
    }

    private static JsonNode parse_input_node(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (IOException ignored) {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.put("raw", raw);
            return wrapper;
        }
    }

    private static String format_auto_compaction_notice(int removed) {
        return "[auto-compacted: removed " + removed + " messages]";
    }

    private static String concatenate_assistant_text(TurnSummary summary) {
        StringBuilder text = new StringBuilder();
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.Text t) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(t.text());
                }
            }
        }
        return text.toString();
    }
}
