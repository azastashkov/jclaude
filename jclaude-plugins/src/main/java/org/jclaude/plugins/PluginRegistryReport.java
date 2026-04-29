package org.jclaude.plugins;

import java.util.List;

/** Result of building a {@link PluginRegistry} alongside any per-plugin load failures. */
public record PluginRegistryReport(PluginRegistry registry, List<PluginLoadFailure> failures) {

    public PluginRegistryReport {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public boolean has_failures() {
        return !failures.isEmpty();
    }

    public List<PluginSummary> summaries() {
        return registry.summaries();
    }

    /** Returns the registry if there are no failures; otherwise raises {@code LoadFailures}. */
    public PluginRegistry into_registry() throws PluginError {
        if (failures.isEmpty()) {
            return registry;
        }
        throw PluginError.load_failures(failures);
    }
}
