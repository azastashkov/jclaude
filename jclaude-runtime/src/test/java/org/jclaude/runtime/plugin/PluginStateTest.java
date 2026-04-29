package org.jclaude.runtime.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginStateTest {

    @Test
    void empty_servers_yields_failed_state() {
        PluginState state = PluginState.from_servers(List.of());

        assertThat(state).isInstanceOf(PluginState.Failed.class);
        assertThat(((PluginState.Failed) state).reason()).contains("no servers");
    }

    @Test
    void all_healthy_servers_yields_healthy_state() {
        PluginState state = PluginState.from_servers(
                List.of(new ServerHealth("alpha", ServerStatus.HEALTHY, List.of("hover"), java.util.Optional.empty())));

        assertThat(state).isInstanceOf(PluginState.Healthy.class);
    }

    @Test
    void mixed_servers_yields_degraded_state() {
        PluginState state = PluginState.from_servers(List.of(
                new ServerHealth("alpha", ServerStatus.HEALTHY, List.of(), java.util.Optional.empty()),
                new ServerHealth("beta", ServerStatus.FAILED, List.of(), java.util.Optional.of("oops"))));

        assertThat(state).isInstanceOf(PluginState.Degraded.class);
        PluginState.Degraded d = (PluginState.Degraded) state;
        assertThat(d.healthy_servers()).containsExactly("alpha");
        assertThat(d.failed_servers()).hasSize(1);
    }

    @Test
    void all_failed_servers_yields_failed_state() {
        PluginState state = PluginState.from_servers(List.of(
                new ServerHealth("alpha", ServerStatus.FAILED, List.of(), java.util.Optional.empty()),
                new ServerHealth("beta", ServerStatus.FAILED, List.of(), java.util.Optional.empty())));

        assertThat(state).isInstanceOf(PluginState.Failed.class);
        assertThat(((PluginState.Failed) state).reason()).contains("2 servers failed");
    }
}
