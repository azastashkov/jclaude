package org.jclaude.runtime.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.session.Session;
import org.junit.jupiter.api.Test;

/** Mechanical port of crates/runtime/src/usage.rs tests. */
final class UsageTrackerTest {

    @Test
    void tracks_true_cumulative_usage() {
        UsageTracker tracker = new UsageTracker();
        tracker.record(new TokenUsage(10, 4, 2, 1));
        tracker.record(new TokenUsage(20, 6, 3, 2));

        assertThat(tracker.turns()).isEqualTo(2);
        assertThat(tracker.current_turn_usage().input_tokens()).isEqualTo(20);
        assertThat(tracker.current_turn_usage().output_tokens()).isEqualTo(6);
        assertThat(tracker.cumulative_usage().output_tokens()).isEqualTo(10);
        assertThat(tracker.cumulative_usage().input_tokens()).isEqualTo(30);
        assertThat(tracker.cumulative_usage().total_tokens()).isEqualTo(48);
    }

    @Test
    void supports_model_specific_pricing() {
        TokenUsage usage = new TokenUsage(1_000_000, 500_000, 0, 0);

        ModelPricing haiku =
                ModelPricing.pricing_for_model("claude-haiku-4-5-20251001").orElseThrow();
        ModelPricing opus = ModelPricing.pricing_for_model("claude-opus-4-6").orElseThrow();
        UsageCostEstimate haiku_cost = usage.estimate_cost_usd_with_pricing(haiku);
        UsageCostEstimate opus_cost = usage.estimate_cost_usd_with_pricing(opus);

        // Haiku: 1M*1.0 + 0.5M*5.0 = 1 + 2.5 = $3.5000
        assertThat(UsageCostEstimate.format_usd(haiku_cost.total_cost_usd())).isEqualTo("$3.5000");
        // Opus: 1M*15 + 0.5M*75 = 15 + 37.5 = $52.5000
        assertThat(UsageCostEstimate.format_usd(opus_cost.total_cost_usd())).isEqualTo("$52.5000");
    }

    @Test
    void reconstructs_usage_from_session_messages() {
        Session session = Session.create();
        session.append_message(new ConversationMessage(
                MessageRole.ASSISTANT, List.of(new ContentBlock.Text("done")), new TokenUsage(5, 2, 1, 0)));

        UsageTracker tracker = UsageTracker.from_session(session);

        assertThat(tracker.turns()).isEqualTo(1);
        assertThat(tracker.cumulative_usage().total_tokens()).isEqualTo(8);
    }

    @Test
    void computes_cost_summary_lines() {
        // Java has format_usd but no summary_lines_for_model — verify the cost components
        // line up with expected values.
        TokenUsage usage = new TokenUsage(1_000_000, 500_000, 100_000, 200_000);

        UsageCostEstimate cost = usage.estimate_cost_usd();

        assertThat(UsageCostEstimate.format_usd(cost.input_cost_usd())).isEqualTo("$15.0000");
        assertThat(UsageCostEstimate.format_usd(cost.output_cost_usd())).isEqualTo("$37.5000");
        // 100_000 * 18.75 / 1M = 1.875 -> $1.8750
        assertThat(UsageCostEstimate.format_usd(cost.cache_creation_cost_usd())).isEqualTo("$1.8750");
        // 200_000 * 1.5 / 1M = 0.3 -> $0.3000
        assertThat(UsageCostEstimate.format_usd(cost.cache_read_cost_usd())).isEqualTo("$0.3000");
        // Total: 15 + 37.5 + 1.875 + 0.3 = 54.675 -> $54.6750
        assertThat(UsageCostEstimate.format_usd(cost.total_cost_usd())).isEqualTo("$54.6750");
    }

    @Test
    void marks_unknown_model_pricing_as_fallback() {
        // Unknown model returns Optional.empty(), and cost estimation falls back to
        // the default sonnet pricing tier.
        Optional<ModelPricing> unknown = ModelPricing.pricing_for_model("custom-model");
        assertThat(unknown).isEmpty();

        TokenUsage usage = new TokenUsage(100, 100, 0, 0);
        UsageCostEstimate fallback = usage.estimate_cost_usd();
        UsageCostEstimate explicit = usage.estimate_cost_usd_with_pricing(ModelPricing.default_sonnet_tier());

        assertThat(UsageCostEstimate.format_usd(fallback.total_cost_usd()))
                .isEqualTo(UsageCostEstimate.format_usd(explicit.total_cost_usd()));
    }
}
