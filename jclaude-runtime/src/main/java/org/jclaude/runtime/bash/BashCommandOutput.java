package org.jclaude.runtime.bash;

/**
 * Phase 1 output returned from a bash tool invocation.
 *
 * <p>This is a reduced subset of the upstream Rust {@code BashCommandOutput}
 * (see {@code claw-code/rust/crates/runtime/src/bash.rs} L39-L68). Fields like
 * {@code rawOutputPath}, {@code isImage}, {@code backgroundTaskId}, {@code sandboxStatus}, and
 * {@code structuredContent} are deferred to later phases of the port.
 *
 * <p>{@code timed_out} corresponds to the {@code interrupted} flag in the upstream record when
 * the cause of interruption is the timeout watchdog.
 */
public record BashCommandOutput(String stdout, String stderr, int exit_code, boolean timed_out) {

    public BashCommandOutput {
        if (stdout == null) {
            throw new IllegalArgumentException("stdout must not be null");
        }
        if (stderr == null) {
            throw new IllegalArgumentException("stderr must not be null");
        }
    }
}
