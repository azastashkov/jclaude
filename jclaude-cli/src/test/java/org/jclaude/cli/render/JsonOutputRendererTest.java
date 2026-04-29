package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.conversation.AutoCompactionEvent;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonOutputRenderer}. The Java MVP CLI's JSON envelope is the canonical shape
 * exercised by the parity harness (see {@code crates/rusty-claude-cli/tests/output_format_contract.rs}).
 * These tests pin the {@code kind=result} envelope, the auto-compaction null/object switch, and
 * tool_use/tool_result emission.
 */
final class JsonOutputRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emits_kind_result_envelope_with_model_and_message() {
        ConversationMessage assistant = ConversationMessage.assistant(List.of(new ContentBlock.Text("hello world")));
        TurnSummary summary = new TurnSummary(
                List.of(assistant), List.of(), List.of(), 1, new TokenUsage(10, 5, 0, 0), Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        assertThat(root.path("kind").asText()).isEqualTo("result");
        assertThat(root.path("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(root.path("message").asText()).isEqualTo("hello world");
        assertThat(root.path("iterations").asInt()).isEqualTo(1);
    }

    @Test
    void concatenates_multi_block_assistant_text_with_newlines() {
        ConversationMessage assistant = ConversationMessage.assistant(
                List.of(new ContentBlock.Text("line one"), new ContentBlock.Text("line two")));
        TurnSummary summary =
                new TurnSummary(List.of(assistant), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        assertThat(root.path("message").asText()).isEqualTo("line one\nline two");
    }

    @Test
    void emits_auto_compaction_object_when_present() {
        // Mirrors the Rust auto-compaction emission contract — the parity
        // harness keys off `auto_compaction.notice` and `removed_messages`.
        TurnSummary summary = new TurnSummary(
                List.of(), List.of(), List.of(), 3, TokenUsage.ZERO, Optional.of(new AutoCompactionEvent(7)));

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        JsonNode auto = root.path("auto_compaction");
        assertThat(auto.isObject()).isTrue();
        assertThat(auto.path("removed_messages").asInt()).isEqualTo(7);
        assertThat(auto.path("notice").asText()).contains("removed 7 messages");
    }

    @Test
    void emits_null_auto_compaction_when_absent() {
        TurnSummary summary = new TurnSummary(List.of(), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        assertThat(root.path("auto_compaction").isNull()).isTrue();
    }

    @Test
    void emits_tool_uses_array_with_id_name_and_parsed_input() {
        ConversationMessage assistant = ConversationMessage.assistant(List.of(
                new ContentBlock.ToolUse("toolu_1", "read_file", "{\"path\":\"/tmp/x.txt\"}"),
                new ContentBlock.Text("done")));
        TurnSummary summary =
                new TurnSummary(List.of(assistant), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        JsonNode uses = root.path("tool_uses");
        assertThat(uses.isArray()).isTrue();
        assertThat(uses).hasSize(1);
        assertThat(uses.get(0).path("id").asText()).isEqualTo("toolu_1");
        assertThat(uses.get(0).path("name").asText()).isEqualTo("read_file");
        assertThat(uses.get(0).path("input").path("path").asText()).isEqualTo("/tmp/x.txt");
    }

    @Test
    void wraps_invalid_tool_input_json_under_raw_field() {
        ConversationMessage assistant = ConversationMessage.assistant(
                List.of(new ContentBlock.ToolUse("toolu_2", "noisy", "this is not json")));
        TurnSummary summary =
                new TurnSummary(List.of(assistant), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        JsonNode input = root.path("tool_uses").get(0).path("input");
        assertThat(input.path("raw").asText()).isEqualTo("this is not json");
    }

    @Test
    void emits_tool_results_array_with_full_metadata() {
        ConversationMessage tool_result = new ConversationMessage(
                MessageRole.TOOL, List.of(new ContentBlock.ToolResult("toolu_3", "bash", "stdout body", false)), null);
        TurnSummary summary =
                new TurnSummary(List.of(), List.of(tool_result), List.of(), 2, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        JsonNode results = root.path("tool_results");
        assertThat(results.isArray()).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("tool_use_id").asText()).isEqualTo("toolu_3");
        assertThat(results.get(0).path("tool_name").asText()).isEqualTo("bash");
        assertThat(results.get(0).path("output").asText()).isEqualTo("stdout body");
        assertThat(results.get(0).path("is_error").asBoolean()).isFalse();
    }

    @Test
    void surfaces_tool_errors_through_is_error_flag() {
        ConversationMessage tool_result = new ConversationMessage(
                MessageRole.TOOL,
                List.of(new ContentBlock.ToolResult("toolu_4", "bash", "command failed", true)),
                null);
        TurnSummary summary =
                new TurnSummary(List.of(), List.of(tool_result), List.of(), 1, TokenUsage.ZERO, Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        assertThat(root.path("tool_results").get(0).path("is_error").asBoolean())
                .isTrue();
    }

    @Test
    void includes_token_usage_object() {
        // Mirrors `usage_tokens_reported_in_json_output` style assertions
        // from the parity harness.
        TurnSummary summary =
                new TurnSummary(List.of(), List.of(), List.of(), 1, new TokenUsage(123, 456, 7, 8), Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        JsonNode usage = root.path("usage");
        assertThat(usage.path("input_tokens").asLong()).isEqualTo(123L);
        assertThat(usage.path("output_tokens").asLong()).isEqualTo(456L);
        assertThat(usage.path("cache_creation_input_tokens").asLong()).isEqualTo(7L);
        assertThat(usage.path("cache_read_input_tokens").asLong()).isEqualTo(8L);
    }

    @Test
    void includes_estimated_cost_string_in_usd_format() {
        TurnSummary summary =
                new TurnSummary(List.of(), List.of(), List.of(), 1, new TokenUsage(1000, 1000, 0, 0), Optional.empty());

        ObjectNode root = JsonOutputRenderer.build(summary, "claude-sonnet-4-6");

        String cost = root.path("estimated_cost").asText();
        assertThat(cost).startsWith("$");
        assertThat(cost).matches("\\$\\d+\\.\\d+");
    }

    @Test
    void render_writes_compact_single_line_json_to_stream() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(buffer)) {
            ConversationMessage assistant = ConversationMessage.assistant(List.of(new ContentBlock.Text("hi")));
            TurnSummary summary =
                    new TurnSummary(List.of(assistant), List.of(), List.of(), 1, TokenUsage.ZERO, Optional.empty());
            new JsonOutputRenderer(stream).render(summary, "claude-sonnet-4-6");
        }
        String written = buffer.toString();
        // Must parse as a single JSON object (not pretty-printed across lines).
        JsonNode parsed = MAPPER.readTree(written);
        assertThat(parsed.path("kind").asText()).isEqualTo("result");
        assertThat(parsed.path("message").asText()).isEqualTo("hi");
    }
}
