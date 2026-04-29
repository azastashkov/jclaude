package org.jclaude.runtime.usage;

public record TokenUsage(
        long input_tokens, long output_tokens, long cache_creation_input_tokens, long cache_read_input_tokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public long total_tokens() {
        return input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens;
    }

    public UsageCostEstimate estimate_cost_usd() {
        return estimate_cost_usd_with_pricing(ModelPricing.default_sonnet_tier());
    }

    public UsageCostEstimate estimate_cost_usd_with_pricing(ModelPricing pricing) {
        return new UsageCostEstimate(
                cost_for_tokens(input_tokens, pricing.input_cost_per_million()),
                cost_for_tokens(output_tokens, pricing.output_cost_per_million()),
                cost_for_tokens(cache_creation_input_tokens, pricing.cache_creation_cost_per_million()),
                cost_for_tokens(cache_read_input_tokens, pricing.cache_read_cost_per_million()));
    }

    private static double cost_for_tokens(long tokens, double usd_per_million) {
        return tokens / 1_000_000.0 * usd_per_million;
    }
}
