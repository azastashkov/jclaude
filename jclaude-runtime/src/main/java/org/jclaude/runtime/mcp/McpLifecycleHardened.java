package org.jclaude.runtime.mcp;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Connect → list_tools → list_resources → ready orchestrator with degraded fallback when one
 * server fails. Mirrors the higher-level orchestration sketched out in
 * {@code mcp_lifecycle_hardened.rs}: validates phase transitions through {@link McpLifecycle},
 * delegates discovery to {@link McpServerManager#discover_tools_best_effort()}, and surfaces a
 * {@link McpToolDiscoveryReport} on failure that callers can consult to keep healthy servers
 * alive.
 */
public final class McpLifecycleHardened {

    private final McpServerManager manager;
    private final McpLifecycle lifecycle;

    public McpLifecycleHardened(McpServerManager manager) {
        this(manager, new McpLifecycle());
    }

    public McpLifecycleHardened(McpServerManager manager, McpLifecycle lifecycle) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public McpLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * Run the canonical startup sequence: ConfigLoad → ServerRegistration → SpawnConnect →
     * InitializeHandshake → ToolDiscovery → Ready, falling back to degraded mode if at least one
     * server fails to bring up. Returns the discovery report (which may include
     * {@code degraded_startup}) on success; on hard failure the caller can inspect the
     * {@link McpLifecycle} state for surfaced errors.
     */
    public McpToolDiscoveryReport startup() {
        lifecycle.run_phase(McpLifecyclePhase.CONFIG_LOAD);
        lifecycle.run_phase(McpLifecyclePhase.SERVER_REGISTRATION);
        lifecycle.run_phase(McpLifecyclePhase.SPAWN_CONNECT);
        lifecycle.run_phase(McpLifecyclePhase.INITIALIZE_HANDSHAKE);
        lifecycle.run_phase(McpLifecyclePhase.TOOL_DISCOVERY);

        McpToolDiscoveryReport report = manager.discover_tools_best_effort();
        if (!report.failed_servers().isEmpty() || !report.unsupported_servers().isEmpty()) {
            // Surface each failure into the lifecycle state so consumers can see the breakdown.
            for (McpDiscoveryFailure failure : report.failed_servers()) {
                lifecycle.record_failure(McpErrorSurface.create(
                        failure.phase(),
                        Optional.of(failure.server_name()),
                        failure.error(),
                        failure.context(),
                        failure.recoverable()));
            }
        }
        if (!report.tools().isEmpty()) {
            lifecycle.run_phase(McpLifecyclePhase.READY);
        }
        return report;
    }

    public void shutdown() {
        lifecycle.run_phase(McpLifecyclePhase.SHUTDOWN);
        manager.shutdown();
        lifecycle.run_phase(McpLifecyclePhase.CLEANUP);
    }

    public List<McpFailedServer> failures() {
        List<McpFailedServer> all = new java.util.ArrayList<>();
        for (McpLifecyclePhase phase : McpLifecyclePhase.values()) {
            for (McpErrorSurface error : lifecycle.errors_for_phase(phase)) {
                all.add(new McpFailedServer(error.server_name().orElse(""), phase, error));
            }
        }
        return List.copyOf(all);
    }
}
