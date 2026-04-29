package org.jclaude.runtime.config;

import java.util.List;
import java.util.Optional;

/** Ordered chain of fallback models. */
public record ProviderFallbackConfig(Optional<String> primary, List<String> fallbacks) {

    public ProviderFallbackConfig {
        primary = primary == null ? Optional.empty() : primary;
        fallbacks = List.copyOf(fallbacks);
    }

    public static ProviderFallbackConfig empty() {
        return new ProviderFallbackConfig(Optional.empty(), List.of());
    }
}
