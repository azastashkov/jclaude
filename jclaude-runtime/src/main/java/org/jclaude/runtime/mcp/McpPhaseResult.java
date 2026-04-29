package org.jclaude.runtime.mcp;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of executing one MCP lifecycle phase. Sealed sum type that maps {@code Success},
 * {@code Failure}, and {@code Timeout} variants from the Rust enum.
 */
public sealed interface McpPhaseResult {

    McpLifecyclePhase phase();

    record Success(McpLifecyclePhase phase, Duration duration) implements McpPhaseResult {

        public Success {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(duration, "duration");
        }
    }

    record Failure(McpLifecyclePhase phase, McpErrorSurface error) implements McpPhaseResult {

        public Failure {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(error, "error");
        }
    }

    record Timeout(McpLifecyclePhase phase, Duration waited, McpErrorSurface error) implements McpPhaseResult {

        public Timeout {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(waited, "waited");
            Objects.requireNonNull(error, "error");
        }
    }
}
