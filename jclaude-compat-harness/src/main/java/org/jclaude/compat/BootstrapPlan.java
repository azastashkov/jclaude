package org.jclaude.compat;

import java.util.List;

/** Ordered list of detected bootstrap phases in the upstream CLI entrypoint. */
public record BootstrapPlan(List<BootstrapPhase> phases) {

    public BootstrapPlan {
        phases = phases == null ? List.of() : List.copyOf(phases);
    }

    public static BootstrapPlan from_phases(List<BootstrapPhase> phases) {
        return new BootstrapPlan(phases);
    }

    public static BootstrapPlan empty() {
        return new BootstrapPlan(List.of());
    }
}
