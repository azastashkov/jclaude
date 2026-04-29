package org.jclaude.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BootstrapPlanTest {

    @Test
    void from_phases_deduplicates_while_preserving_order() {
        List<BootstrapPhase> phases = List.of(
                BootstrapPhase.CLI_ENTRY,
                BootstrapPhase.FAST_PATH_VERSION,
                BootstrapPhase.CLI_ENTRY,
                BootstrapPhase.MAIN_RUNTIME,
                BootstrapPhase.FAST_PATH_VERSION);

        BootstrapPlan plan = BootstrapPlan.from_phases(phases);

        assertThat(plan.phases())
                .containsExactly(
                        BootstrapPhase.CLI_ENTRY, BootstrapPhase.FAST_PATH_VERSION, BootstrapPhase.MAIN_RUNTIME);
    }

    @Test
    void claude_code_default_covers_each_phase_once() {
        List<BootstrapPhase> expected = List.of(
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
                BootstrapPhase.MAIN_RUNTIME);

        BootstrapPlan plan = BootstrapPlan.claude_code_default();

        assertThat(plan.phases()).containsExactlyElementsOf(expected);
    }
}
