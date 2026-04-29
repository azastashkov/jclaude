package org.jclaude.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class McpLifecycleTest {

    @Test
    void given_startup_path_when_running_to_cleanup_then_each_control_transition_succeeds() {
        McpLifecycle validator = new McpLifecycle();
        McpLifecyclePhase[] phases = {
            McpLifecyclePhase.CONFIG_LOAD,
            McpLifecyclePhase.SERVER_REGISTRATION,
            McpLifecyclePhase.SPAWN_CONNECT,
            McpLifecyclePhase.INITIALIZE_HANDSHAKE,
            McpLifecyclePhase.TOOL_DISCOVERY,
            McpLifecyclePhase.RESOURCE_DISCOVERY,
            McpLifecyclePhase.READY,
            McpLifecyclePhase.INVOCATION,
            McpLifecyclePhase.READY,
            McpLifecyclePhase.SHUTDOWN,
            McpLifecyclePhase.CLEANUP,
        };

        for (McpLifecyclePhase phase : phases) {
            McpPhaseResult result = validator.run_phase(phase);
            assertThat(result).isInstanceOf(McpPhaseResult.Success.class);
        }

        assertThat(validator.current_phase()).contains(McpLifecyclePhase.CLEANUP);
        for (McpLifecyclePhase phase : phases) {
            assertThat(validator.phase_timestamp(phase)).isPresent();
        }
    }

    @Test
    void given_tool_discovery_when_resource_discovery_is_skipped_then_ready_is_still_allowed() {
        McpLifecycle validator = new McpLifecycle();
        for (McpLifecyclePhase phase : new McpLifecyclePhase[] {
            McpLifecyclePhase.CONFIG_LOAD,
            McpLifecyclePhase.SERVER_REGISTRATION,
            McpLifecyclePhase.SPAWN_CONNECT,
            McpLifecyclePhase.INITIALIZE_HANDSHAKE,
            McpLifecyclePhase.TOOL_DISCOVERY,
        }) {
            assertThat(validator.run_phase(phase)).isInstanceOf(McpPhaseResult.Success.class);
        }
        assertThat(validator.run_phase(McpLifecyclePhase.READY)).isInstanceOf(McpPhaseResult.Success.class);
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.READY);
    }

    @Test
    void validates_expected_phase_transitions() {
        assertThat(McpLifecycle.validate_phase_transition(
                        McpLifecyclePhase.CONFIG_LOAD, McpLifecyclePhase.SERVER_REGISTRATION))
                .isTrue();
        assertThat(McpLifecycle.validate_phase_transition(McpLifecyclePhase.TOOL_DISCOVERY, McpLifecyclePhase.READY))
                .isTrue();
        assertThat(McpLifecycle.validate_phase_transition(McpLifecyclePhase.READY, McpLifecyclePhase.SHUTDOWN))
                .isTrue();
        assertThat(McpLifecycle.validate_phase_transition(
                        McpLifecyclePhase.INVOCATION, McpLifecyclePhase.ERROR_SURFACING))
                .isTrue();
        assertThat(McpLifecycle.validate_phase_transition(McpLifecyclePhase.READY, McpLifecyclePhase.CONFIG_LOAD))
                .isFalse();
        assertThat(McpLifecycle.validate_phase_transition(McpLifecyclePhase.CLEANUP, McpLifecyclePhase.READY))
                .isFalse();
    }

    @Test
    void given_invalid_transition_when_running_phase_then_structured_failure_is_recorded() {
        McpLifecycle validator = new McpLifecycle();
        validator.run_phase(McpLifecyclePhase.CONFIG_LOAD);
        validator.run_phase(McpLifecyclePhase.SERVER_REGISTRATION);

        McpPhaseResult result = validator.run_phase(McpLifecyclePhase.READY);

        assertThat(result).isInstanceOf(McpPhaseResult.Failure.class);
        McpPhaseResult.Failure failure = (McpPhaseResult.Failure) result;
        assertThat(failure.phase()).isEqualTo(McpLifecyclePhase.READY);
        assertThat(failure.error().recoverable()).isFalse();
        assertThat(failure.error().context()).containsEntry("from", "server_registration");
        assertThat(failure.error().context()).containsEntry("to", "ready");
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.ERROR_SURFACING);
        assertThat(validator.errors_for_phase(McpLifecyclePhase.READY)).hasSize(1);
    }

    @Test
    void given_each_phase_when_failure_is_recorded_then_error_is_tracked_per_phase() {
        McpLifecycle validator = new McpLifecycle();
        for (McpLifecyclePhase phase : McpLifecyclePhase.values()) {
            McpPhaseResult result = validator.record_failure(McpErrorSurface.create(
                    phase,
                    Optional.of("alpha"),
                    "failure at " + phase,
                    Map.of("server", "alpha"),
                    phase == McpLifecyclePhase.RESOURCE_DISCOVERY));

            assertThat(result).isInstanceOf(McpPhaseResult.Failure.class);
            McpPhaseResult.Failure failure = (McpPhaseResult.Failure) result;
            assertThat(failure.phase()).isEqualTo(phase);
            assertThat(failure.error().phase()).isEqualTo(phase);
            assertThat(failure.error().recoverable()).isEqualTo(phase == McpLifecyclePhase.RESOURCE_DISCOVERY);
            assertThat(validator.errors_for_phase(phase)).hasSize(1);
        }
    }

    @Test
    void given_spawn_connect_timeout_when_recorded_then_waited_duration_is_preserved() {
        McpLifecycle validator = new McpLifecycle();
        Duration waited = Duration.ofMillis(250);

        McpPhaseResult result = validator.record_timeout(
                McpLifecyclePhase.SPAWN_CONNECT, waited, Optional.of("alpha"), Map.of("attempt", "1"));

        assertThat(result).isInstanceOf(McpPhaseResult.Timeout.class);
        McpPhaseResult.Timeout timeout = (McpPhaseResult.Timeout) result;
        assertThat(timeout.phase()).isEqualTo(McpLifecyclePhase.SPAWN_CONNECT);
        assertThat(timeout.waited()).isEqualTo(waited);
        assertThat(timeout.error().recoverable()).isTrue();
        assertThat(timeout.error().server_name()).contains("alpha");
        assertThat(validator.errors_for_phase(McpLifecyclePhase.SPAWN_CONNECT)).hasSize(1);
        assertThat(validator
                        .errors_for_phase(McpLifecyclePhase.SPAWN_CONNECT)
                        .get(0)
                        .context())
                .containsEntry("waited_ms", "250");
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.ERROR_SURFACING);
    }

    @Test
    void given_partial_server_health_when_building_degraded_report_then_missing_tools_are_reported() {
        McpFailedServer failed = new McpFailedServer(
                "broken",
                McpLifecyclePhase.INITIALIZE_HANDSHAKE,
                McpErrorSurface.create(
                        McpLifecyclePhase.INITIALIZE_HANDSHAKE,
                        Optional.of("broken"),
                        "initialize failed",
                        Map.of("reason", "broken pipe"),
                        false));

        McpDegradedReport report = McpDegradedReport.create(
                java.util.List.of("alpha", "beta", "alpha"),
                java.util.List.of(failed),
                java.util.List.of("alpha.echo", "beta.search", "alpha.echo"),
                java.util.List.of("alpha.echo", "beta.search", "broken.fetch"));

        assertThat(report.working_servers()).containsExactly("alpha", "beta");
        assertThat(report.failed_servers()).hasSize(1);
        assertThat(report.failed_servers().get(0).server_name()).isEqualTo("broken");
        assertThat(report.available_tools()).containsExactly("alpha.echo", "beta.search");
        assertThat(report.missing_tools()).containsExactly("broken.fetch");
    }

    @Test
    void given_failure_during_resource_discovery_when_shutting_down_then_cleanup_still_succeeds() {
        McpLifecycle validator = new McpLifecycle();
        for (McpLifecyclePhase phase : new McpLifecyclePhase[] {
            McpLifecyclePhase.CONFIG_LOAD,
            McpLifecyclePhase.SERVER_REGISTRATION,
            McpLifecyclePhase.SPAWN_CONNECT,
            McpLifecyclePhase.INITIALIZE_HANDSHAKE,
            McpLifecyclePhase.TOOL_DISCOVERY,
        }) {
            assertThat(validator.run_phase(phase)).isInstanceOf(McpPhaseResult.Success.class);
        }
        validator.record_failure(McpErrorSurface.create(
                McpLifecyclePhase.RESOURCE_DISCOVERY,
                Optional.of("alpha"),
                "resource listing failed",
                Map.of("reason", "timeout"),
                true));

        McpPhaseResult shutdown = validator.run_phase(McpLifecyclePhase.SHUTDOWN);
        McpPhaseResult cleanup = validator.run_phase(McpLifecyclePhase.CLEANUP);

        assertThat(shutdown).isInstanceOf(McpPhaseResult.Success.class);
        assertThat(cleanup).isInstanceOf(McpPhaseResult.Success.class);
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.CLEANUP);
        assertThat(validator.phase_timestamp(McpLifecyclePhase.ERROR_SURFACING)).isPresent();
    }

    @Test
    void error_surface_display_includes_phase_server_and_recoverable_flag() {
        McpErrorSurface error = McpErrorSurface.create(
                McpLifecyclePhase.SPAWN_CONNECT,
                Optional.of("alpha"),
                "process exited early",
                Map.of("exit_code", "1"),
                true);

        String rendered = error.toString();

        assertThat(rendered).contains("spawn_connect");
        assertThat(rendered).contains("process exited early");
        assertThat(rendered).contains("server: alpha");
        assertThat(rendered).contains("recoverable");
    }

    @Test
    void given_nonrecoverable_failure_when_returning_to_ready_then_validator_rejects_resume() {
        McpLifecycle validator = new McpLifecycle();
        for (McpLifecyclePhase phase : new McpLifecyclePhase[] {
            McpLifecyclePhase.CONFIG_LOAD,
            McpLifecyclePhase.SERVER_REGISTRATION,
            McpLifecyclePhase.SPAWN_CONNECT,
            McpLifecyclePhase.INITIALIZE_HANDSHAKE,
            McpLifecyclePhase.TOOL_DISCOVERY,
            McpLifecyclePhase.READY,
        }) {
            assertThat(validator.run_phase(phase)).isInstanceOf(McpPhaseResult.Success.class);
        }
        validator.record_failure(McpErrorSurface.create(
                McpLifecyclePhase.INVOCATION,
                Optional.of("alpha"),
                "tool call corrupted the session",
                Map.of("reason", "invalid frame"),
                false));

        McpPhaseResult result = validator.run_phase(McpLifecyclePhase.READY);

        assertThat(result).isInstanceOf(McpPhaseResult.Failure.class);
        McpPhaseResult.Failure failure = (McpPhaseResult.Failure) result;
        assertThat(failure.phase()).isEqualTo(McpLifecyclePhase.READY);
        assertThat(failure.error().recoverable()).isFalse();
        assertThat(failure.error().message()).contains("non-recoverable");
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.ERROR_SURFACING);
    }

    @Test
    void given_recoverable_failure_when_returning_to_ready_then_validator_allows_resume() {
        McpLifecycle validator = new McpLifecycle();
        for (McpLifecyclePhase phase : new McpLifecyclePhase[] {
            McpLifecyclePhase.CONFIG_LOAD,
            McpLifecyclePhase.SERVER_REGISTRATION,
            McpLifecyclePhase.SPAWN_CONNECT,
            McpLifecyclePhase.INITIALIZE_HANDSHAKE,
            McpLifecyclePhase.TOOL_DISCOVERY,
            McpLifecyclePhase.READY,
        }) {
            assertThat(validator.run_phase(phase)).isInstanceOf(McpPhaseResult.Success.class);
        }
        validator.record_failure(McpErrorSurface.create(
                McpLifecyclePhase.INVOCATION,
                Optional.of("alpha"),
                "tool call failed but can be retried",
                Map.of("reason", "upstream timeout"),
                true));

        McpPhaseResult result = validator.run_phase(McpLifecyclePhase.READY);

        assertThat(result).isInstanceOf(McpPhaseResult.Success.class);
        assertThat(validator.current_phase()).contains(McpLifecyclePhase.READY);
    }
}
