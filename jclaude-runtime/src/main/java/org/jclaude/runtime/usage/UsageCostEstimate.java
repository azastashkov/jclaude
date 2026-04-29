package org.jclaude.runtime.usage;

public record UsageCostEstimate(
        double input_cost_usd, double output_cost_usd, double cache_creation_cost_usd, double cache_read_cost_usd) {

    public double total_cost_usd() {
        return input_cost_usd + output_cost_usd + cache_creation_cost_usd + cache_read_cost_usd;
    }

    public static String format_usd(double amount) {
        return String.format(java.util.Locale.ROOT, "$%.4f", amount);
    }
}
