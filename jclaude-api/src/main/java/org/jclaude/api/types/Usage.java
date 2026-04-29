package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jclaude.runtime.usage.ModelPricing;
import org.jclaude.runtime.usage.TokenUsage;
import org.jclaude.runtime.usage.UsageCostEstimate;

public record Usage(
        long input_tokens, long cache_creation_input_tokens, long cache_read_input_tokens, long output_tokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    @JsonCreator
    public Usage(
            @JsonProperty("input_tokens") Long input_tokens,
            @JsonProperty("cache_creation_input_tokens") Long cache_creation_input_tokens,
            @JsonProperty("cache_read_input_tokens") Long cache_read_input_tokens,
            @JsonProperty("output_tokens") Long output_tokens) {
        this(
                input_tokens == null ? 0 : input_tokens,
                cache_creation_input_tokens == null ? 0 : cache_creation_input_tokens,
                cache_read_input_tokens == null ? 0 : cache_read_input_tokens,
                output_tokens == null ? 0 : output_tokens);
    }

    public long total_tokens() {
        return input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens;
    }

    public TokenUsage token_usage() {
        return new TokenUsage(input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens);
    }

    public UsageCostEstimate estimated_cost_usd(String model) {
        TokenUsage usage = token_usage();
        return ModelPricing.pricing_for_model(model)
                .map(usage::estimate_cost_usd_with_pricing)
                .orElseGet(usage::estimate_cost_usd);
    }
}
