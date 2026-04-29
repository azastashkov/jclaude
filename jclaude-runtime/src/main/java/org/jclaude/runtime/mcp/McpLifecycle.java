package org.jclaude.runtime.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Phase validator and recorder for MCP lifecycle transitions. Mirrors the Rust
 * {@code McpLifecycleValidator} from {@code mcp_lifecycle_hardened.rs} including the "no resume to
 * Ready after non-recoverable failure" rule.
 */
public final class McpLifecycle {

    private final Object lock = new Object();
    private McpLifecyclePhase current_phase;
    private final Map<McpLifecyclePhase, List<McpErrorSurface>> phase_errors = new TreeMap<>();
    private final Map<McpLifecyclePhase, Long> phase_timestamps = new TreeMap<>();
    private final List<McpPhaseResult> phase_results = new ArrayList<>();

    public McpLifecycle() {}

    public Optional<McpLifecyclePhase> current_phase() {
        synchronized (lock) {
            return Optional.ofNullable(current_phase);
        }
    }

    public List<McpErrorSurface> errors_for_phase(McpLifecyclePhase phase) {
        synchronized (lock) {
            return List.copyOf(phase_errors.getOrDefault(phase, List.of()));
        }
    }

    public List<McpPhaseResult> results() {
        synchronized (lock) {
            return List.copyOf(phase_results);
        }
    }

    public Optional<Long> phase_timestamp(McpLifecyclePhase phase) {
        synchronized (lock) {
            return Optional.ofNullable(phase_timestamps.get(phase));
        }
    }

    public static boolean validate_phase_transition(McpLifecyclePhase from, McpLifecyclePhase to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to == McpLifecyclePhase.SHUTDOWN) {
            return from != McpLifecyclePhase.CLEANUP;
        }
        if (to == McpLifecyclePhase.ERROR_SURFACING) {
            return from != McpLifecyclePhase.CLEANUP && from != McpLifecyclePhase.SHUTDOWN;
        }
        return switch (from) {
            case CONFIG_LOAD -> to == McpLifecyclePhase.SERVER_REGISTRATION;
            case SERVER_REGISTRATION -> to == McpLifecyclePhase.SPAWN_CONNECT;
            case SPAWN_CONNECT -> to == McpLifecyclePhase.INITIALIZE_HANDSHAKE;
            case INITIALIZE_HANDSHAKE -> to == McpLifecyclePhase.TOOL_DISCOVERY;
            case TOOL_DISCOVERY -> to == McpLifecyclePhase.RESOURCE_DISCOVERY || to == McpLifecyclePhase.READY;
            case RESOURCE_DISCOVERY -> to == McpLifecyclePhase.READY;
            case READY -> to == McpLifecyclePhase.INVOCATION;
            case INVOCATION -> to == McpLifecyclePhase.READY;
            case ERROR_SURFACING -> to == McpLifecyclePhase.READY || to == McpLifecyclePhase.SHUTDOWN;
            case SHUTDOWN -> to == McpLifecyclePhase.CLEANUP;
            default -> false;
        };
    }

    public McpPhaseResult run_phase(McpLifecyclePhase phase) {
        Objects.requireNonNull(phase, "phase");
        Instant started = Instant.now();
        synchronized (lock) {
            if (current_phase != null) {
                if (current_phase == McpLifecyclePhase.ERROR_SURFACING
                        && phase == McpLifecyclePhase.READY
                        && !canResumeAfterError()) {
                    return record_failure(McpErrorSurface.create(
                            phase,
                            Optional.empty(),
                            "cannot return to ready after a non-recoverable MCP lifecycle failure",
                            Map.of("from", current_phase.toString(), "to", phase.toString()),
                            false));
                }
                if (!validate_phase_transition(current_phase, phase)) {
                    return record_failure(McpErrorSurface.create(
                            phase,
                            Optional.empty(),
                            "invalid MCP lifecycle transition from " + current_phase + " to " + phase,
                            Map.of("from", current_phase.toString(), "to", phase.toString()),
                            false));
                }
            } else if (phase != McpLifecyclePhase.CONFIG_LOAD) {
                return record_failure(McpErrorSurface.create(
                        phase,
                        Optional.empty(),
                        "invalid initial MCP lifecycle phase " + phase,
                        Map.of("phase", phase.toString()),
                        false));
            }

            recordPhase(phase);
            McpPhaseResult result = new McpPhaseResult.Success(phase, Duration.between(started, Instant.now()));
            phase_results.add(result);
            return result;
        }
    }

    public McpPhaseResult record_failure(McpErrorSurface error) {
        Objects.requireNonNull(error, "error");
        synchronized (lock) {
            McpLifecyclePhase phase = error.phase();
            phase_errors.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(error);
            recordPhase(McpLifecyclePhase.ERROR_SURFACING);
            McpPhaseResult result = new McpPhaseResult.Failure(phase, error);
            phase_results.add(result);
            return result;
        }
    }

    public McpPhaseResult record_timeout(
            McpLifecyclePhase phase, Duration waited, Optional<String> server_name, Map<String, String> context) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(waited, "waited");
        Objects.requireNonNull(server_name, "server_name");
        Map<String, String> merged = new TreeMap<>(context);
        merged.put("waited_ms", String.valueOf(waited.toMillis()));
        McpErrorSurface error = McpErrorSurface.create(
                phase,
                server_name,
                "MCP lifecycle phase " + phase + " timed out after " + waited.toMillis() + " ms",
                merged,
                true);
        synchronized (lock) {
            phase_errors.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(error);
            recordPhase(McpLifecyclePhase.ERROR_SURFACING);
            McpPhaseResult result = new McpPhaseResult.Timeout(phase, waited, error);
            phase_results.add(result);
            return result;
        }
    }

    private void recordPhase(McpLifecyclePhase phase) {
        current_phase = phase;
        phase_timestamps.put(phase, Instant.now().getEpochSecond());
    }

    private boolean canResumeAfterError() {
        if (phase_results.isEmpty()) {
            return false;
        }
        McpPhaseResult last = phase_results.get(phase_results.size() - 1);
        return switch (last) {
            case McpPhaseResult.Failure f -> f.error().recoverable();
            case McpPhaseResult.Timeout t -> t.error().recoverable();
            default -> false;
        };
    }

    /** Snapshot view of all phase timestamps (epoch seconds). */
    public Map<McpLifecyclePhase, Long> phase_timestamps() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new TreeMap<>(phase_timestamps));
        }
    }
}
