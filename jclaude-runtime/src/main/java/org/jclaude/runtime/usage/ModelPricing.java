package org.jclaude.runtime.usage;

import java.util.Locale;
import java.util.Optional;

public record ModelPricing(
        double input_cost_per_million,
        double output_cost_per_million,
        double cache_creation_cost_per_million,
        double cache_read_cost_per_million) {

    private static final double DEFAULT_INPUT_COST_PER_MILLION = 15.0;
    private static final double DEFAULT_OUTPUT_COST_PER_MILLION = 75.0;
    private static final double DEFAULT_CACHE_CREATION_COST_PER_MILLION = 18.75;
    private static final double DEFAULT_CACHE_READ_COST_PER_MILLION = 1.5;

    public static ModelPricing default_sonnet_tier() {
        return new ModelPricing(
                DEFAULT_INPUT_COST_PER_MILLION,
                DEFAULT_OUTPUT_COST_PER_MILLION,
                DEFAULT_CACHE_CREATION_COST_PER_MILLION,
                DEFAULT_CACHE_READ_COST_PER_MILLION);
    }

    public static Optional<ModelPricing> pricing_for_model(String model) {
        if (model == null) {
            return Optional.empty();
        }
        String n = model.toLowerCase(Locale.ROOT);
        if (n.contains("haiku")) {
            return Optional.of(new ModelPricing(1.0, 5.0, 1.25, 0.1));
        }
        if (n.contains("opus")) {
            return Optional.of(new ModelPricing(15.0, 75.0, 18.75, 1.5));
        }
        if (n.contains("sonnet")) {
            return Optional.of(default_sonnet_tier());
        }
        return Optional.empty();
    }
}
