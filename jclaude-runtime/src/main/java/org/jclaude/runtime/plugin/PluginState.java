package org.jclaude.runtime.plugin;

import java.util.ArrayList;
import java.util.List;

/** State of a plugin in its lifecycle. */
public sealed interface PluginState {

    record Unconfigured() implements PluginState {}

    record Validated() implements PluginState {}

    record Starting() implements PluginState {}

    record Healthy() implements PluginState {}

    record Degraded(List<String> healthy_servers, List<ServerHealth> failed_servers) implements PluginState {
        public Degraded {
            healthy_servers = List.copyOf(healthy_servers);
            failed_servers = List.copyOf(failed_servers);
        }
    }

    record Failed(String reason) implements PluginState {}

    record ShuttingDown() implements PluginState {}

    record Stopped() implements PluginState {}

    static PluginState from_servers(List<ServerHealth> servers) {
        if (servers.isEmpty()) {
            return new Failed("no servers available");
        }
        List<String> healthy = new ArrayList<>();
        List<ServerHealth> failed = new ArrayList<>();
        boolean has_degraded = false;
        for (ServerHealth s : servers) {
            if (s.status() != ServerStatus.FAILED) {
                healthy.add(s.server_name());
            }
            if (s.status() == ServerStatus.FAILED) {
                failed.add(s);
            }
            if (s.status() == ServerStatus.DEGRADED) {
                has_degraded = true;
            }
        }
        if (failed.isEmpty() && !has_degraded) {
            return new Healthy();
        }
        if (healthy.isEmpty()) {
            return new Failed("all " + failed.size() + " servers failed");
        }
        return new Degraded(healthy, failed);
    }
}
