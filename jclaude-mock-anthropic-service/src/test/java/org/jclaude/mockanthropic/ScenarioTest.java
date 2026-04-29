package org.jclaude.mockanthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.ToolResultContentBlock;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    @Test
    void scenario_prefix_constant_matches_rust() {
        assertThat(Scenario.SCENARIO_PREFIX).isEqualTo("PARITY_SCENARIO:");
    }

    @Test
    void all_twelve_scenarios_round_trip_through_from_name() {
        String[] names = {
            "streaming_text",
            "read_file_roundtrip",
            "grep_chunk_assembly",
            "write_file_allowed",
            "write_file_denied",
            "multi_tool_turn_roundtrip",
            "bash_stdout_roundtrip",
            "bash_permission_prompt_approved",
            "bash_permission_prompt_denied",
            "plugin_tool_roundtrip",
            "auto_compact_triggered",
            "token_cost_reporting"
        };
        assertThat(names).hasSameSizeAs(Scenario.values());
        for (String name : names) {
            Optional<Scenario> resolved = Scenario.from_name(name);
            assertThat(resolved).as("from_name(%s)", name).isPresent();
            assertThat(resolved.orElseThrow().wire_name()).isEqualTo(name);
        }
    }

    @Test
    void from_name_returns_empty_for_unknown_value() {
        assertThat(Scenario.from_name("nope")).isEmpty();
        assertThat(Scenario.from_name(null)).isEmpty();
    }

    @Test
    void from_user_text_picks_up_prefixed_token_anywhere_in_string() {
        Optional<Scenario> resolved = Scenario.from_user_text("hello PARITY_SCENARIO:streaming_text world");
        assertThat(resolved).contains(Scenario.STREAMING_TEXT);
    }

    @Test
    void from_user_text_returns_empty_when_no_marker_present() {
        assertThat(Scenario.from_user_text("nothing to see here")).isEmpty();
        assertThat(Scenario.from_user_text(null)).isEmpty();
    }

    @Test
    void detect_walks_messages_in_reverse_order_and_finds_latest_marker() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:streaming_text"),
                        InputMessage.user_text("PARITY_SCENARIO:read_file_roundtrip")))
                .build();
        assertThat(Scenario.detect(request)).contains(Scenario.READ_FILE_ROUNDTRIP);
    }

    @Test
    void detect_skips_tool_result_blocks_and_looks_only_at_text() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of(
                        InputMessage.user_text("PARITY_SCENARIO:bash_stdout_roundtrip"),
                        new InputMessage(
                                "user",
                                List.of(new InputContentBlock.ToolResult(
                                        "toolu_x",
                                        List.of(new ToolResultContentBlock.Text("{\"stdout\":\"hello\"}")),
                                        false)))))
                .build();
        assertThat(Scenario.detect(request)).contains(Scenario.BASH_STDOUT_ROUNDTRIP);
    }

    @Test
    void detect_returns_empty_when_no_text_block_marker_present() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of(InputMessage.user_text("hello")))
                .build();
        assertThat(Scenario.detect(request)).isEmpty();
    }
}
