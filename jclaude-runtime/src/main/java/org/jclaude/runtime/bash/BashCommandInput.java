package org.jclaude.runtime.bash;

import java.nio.file.Path;

/**
 * Phase 1 input schema for the built-in bash execution tool.
 *
 * <p>This is a deliberately reduced subset of the upstream Rust {@code BashCommandInput}
 * (see {@code claw-code/rust/crates/runtime/src/bash.rs} L19-L36). Sandbox knobs
 * ({@code dangerouslyDisableSandbox}, {@code namespaceRestrictions}, {@code isolateNetwork},
 * {@code filesystemMode}, {@code allowedMounts}) and the bash validation submodules referenced by
 * the dispatcher are deferred to Phase 3 of the port.
 *
 * <p>{@code cwd} is optional; {@code null} means inherit the current process working directory.
 * {@code background} is currently a stub — Phase 1 always runs synchronously even when set.
 */
public record BashCommandInput(String command, Path cwd, long timeout_ms, boolean background) {

    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    public BashCommandInput {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (timeout_ms <= 0) {
            throw new IllegalArgumentException("timeout_ms must be positive, got " + timeout_ms);
        }
    }

    /** Convenience factory: command only, default timeout, no cwd, foreground. */
    public static BashCommandInput of(String command) {
        return new BashCommandInput(command, null, DEFAULT_TIMEOUT_MS, false);
    }

    /** Convenience factory: command + explicit timeout. */
    public static BashCommandInput of(String command, long timeout_ms) {
        return new BashCommandInput(command, null, timeout_ms, false);
    }
}
