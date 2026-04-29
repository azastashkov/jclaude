package org.jclaude.runtime.boot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Bootstrap plan record holding deduplicated phases preserving insertion order. */
public final class BootstrapPlan {

    private final List<BootstrapPhase> phases;

    private BootstrapPlan(List<BootstrapPhase> phases) {
        this.phases = List.copyOf(phases);
    }

    public static BootstrapPlan claude_code_default() {
        return from_phases(List.of(
                BootstrapPhase.CLI_ENTRY,
                BootstrapPhase.FAST_PATH_VERSION,
                BootstrapPhase.STARTUP_PROFILER,
                BootstrapPhase.SYSTEM_PROMPT_FAST_PATH,
                BootstrapPhase.CHROME_MCP_FAST_PATH,
                BootstrapPhase.DAEMON_WORKER_FAST_PATH,
                BootstrapPhase.BRIDGE_FAST_PATH,
                BootstrapPhase.DAEMON_FAST_PATH,
                BootstrapPhase.BACKGROUND_SESSION_FAST_PATH,
                BootstrapPhase.TEMPLATE_FAST_PATH,
                BootstrapPhase.ENVIRONMENT_RUNNER_FAST_PATH,
                BootstrapPhase.MAIN_RUNTIME));
    }

    public static BootstrapPlan from_phases(List<BootstrapPhase> phases) {
        LinkedHashSet<BootstrapPhase> dedup = new LinkedHashSet<>(phases);
        return new BootstrapPlan(new ArrayList<>(dedup));
    }

    public List<BootstrapPhase> phases() {
        return phases;
    }
}
