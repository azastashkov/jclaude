package org.jclaude.runtime.bash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1 port of {@code claw-code/rust/crates/runtime/src/bash.rs} {@code execute_bash}.
 *
 * <p>Executes a shell command via {@code /bin/sh -c "<command>"}, captures stdout and stderr
 * concurrently on virtual threads (to avoid OS-pipe-buffer deadlocks under heavy output), and
 * enforces a wall-clock timeout via {@link Process#waitFor(long, TimeUnit)}. On timeout the
 * process is forcibly destroyed and {@link BashCommandOutput#timed_out()} is set.
 *
 * <p>TODO(Phase 3): wire in {@code BashValidator} once the 9 validation submodules from Rust's
 * {@code bash_validation.rs} are ported. See {@code claw-code/rust/crates/runtime/src/bash.rs}
 * L168-L249 for the upstream sandbox + validation pipeline.
 */
public final class Bash {

    /** POSIX shell used to interpret the command string. */
    static final String SHELL = "/bin/sh";

    private Bash() {}

    /**
     * Executes {@code input.command()} and returns captured stdout/stderr plus the exit code.
     *
     * <p>This method prefers returning a {@link BashCommandOutput} with a non-zero
     * {@code exit_code} over throwing for ordinary command failures. {@link IOException} is only
     * thrown if the process cannot be spawned or its streams cannot be read.
     */
    public static BashCommandOutput execute(BashCommandInput input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        ProcessBuilder pb = new ProcessBuilder(SHELL, "-c", input.command());
        pb.redirectErrorStream(false);
        if (input.cwd() != null) {
            Path cwd = input.cwd();
            pb.directory(cwd.toFile());
        }

        Process process = pb.start();
        // The process never reads stdin in Phase 1; close it eagerly so commands that try to read
        // (e.g. `cat`) return EOF immediately instead of hanging the timeout.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Closing the child's stdin can race with the child exiting; nothing useful to do.
        }

        try (ExecutorService streamReaders = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdoutFuture = streamReaders.submit(() -> readAllBytes(process.getInputStream()));
            Future<byte[]> stderrFuture = streamReaders.submit(() -> readAllBytes(process.getErrorStream()));

            boolean exited = process.waitFor(input.timeout_ms(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                // Give the child a brief grace period to actually exit before we try to drain
                // its captured streams; readers are blocked in read() and will return once the
                // descriptors close on process death.
                process.waitFor(2, TimeUnit.SECONDS);
                String partialStdout = drainQuietly(stdoutFuture);
                String partialStderr = drainQuietly(stderrFuture);
                return new BashCommandOutput(partialStdout, partialStderr, -1, true);
            }

            String stdout = new String(stdoutFuture.get(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.get(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            return new BashCommandOutput(stdout, stderr, exitCode, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while waiting for bash command", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof UncheckedIOException uio) {
                throw uio.getCause();
            }
            throw new IOException("failed to read bash command output", cause);
        }
    }

    private static byte[] readAllBytes(InputStream in) {
        try (InputStream stream = in;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8 * 1024];
            int n;
            while ((n = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String drainQuietly(Future<byte[]> future) {
        try {
            byte[] bytes = future.get(2, TimeUnit.SECONDS);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
