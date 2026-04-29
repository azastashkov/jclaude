package org.jclaude.api.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jclaude.runtime.usage.UsageCostEstimate;
import org.junit.jupiter.api.Test;

/**
 * Java port of {@code rust/crates/api/src/types.rs} unit tests.
 */
class TypesTest {

    @Test
    void usage_total_tokens_includes_cache_tokens() {
        Usage usage = new Usage(10L, 2L, 3L, 4L);

        assertThat(usage.total_tokens()).isEqualTo(19L);
        assertThat(usage.token_usage().total_tokens()).isEqualTo(19L);
    }

    @Test
    void message_response_estimates_cost_from_model_usage() {
        MessageResponse response = new MessageResponse(
                "msg_cost",
                "message",
                "assistant",
                List.of(),
                "claude-sonnet-4-20250514",
                "end_turn",
                null,
                new Usage(1_000_000L, 100_000L, 200_000L, 500_000L),
                null);

        UsageCostEstimate cost = response.usage().estimated_cost_usd(response.model());
        assertThat(UsageCostEstimate.format_usd(cost.total_cost_usd())).isEqualTo("$54.6750");
        assertThat(response.total_tokens()).isEqualTo(1_800_000L);
    }

    @Test
    void message_request_stream_helper_sets_stream_true() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of(InputMessage.user_text("Hi")))
                .stream(false)
                .build();

        MessageRequest streaming = request.with_streaming();
        assertThat(streaming.stream()).isTrue();
        assertThat(request.stream()).isFalse();
    }
}
