package org.jclaude.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Helper for invoking the installed {@code jclaude} binary in integration tests, mirroring the
 * {@code crates/rusty-claude-cli/tests/*.rs} subprocess pattern. The Java analogue of
 * {@code crates/rusty-claude-cli/tests/cli_flags_and_config_defaults.rs}'s {@code command_in} +
 * {@code run_claw} helpers.
 *
 * <p>This is sister to {@link org.jclaude.cli.ollama.JclaudeProcess} — same locator, but no Ollama
 * defaults baked into the env. Each call must declare the env it needs explicitly.
 */
public final class JclaudeBinary {

    /** Captured stdout/stderr/exit-code from a single jclaude invocation. */
    public record Result(int exitCode, String stdout, String stderr) {}

    private JclaudeBinary() {}

    public static Result run(Path cwd, Map<String, String> env_overrides, String stdin, String... args)
            throws Exception {
        Path binary = locate_binary();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        for (String a : args) {
            cmd.add(a);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", "/usr/bin:/bin:/usr/local/bin");
        env.put("NO_COLOR", "1");
        // Generated bin/jclaude shell launcher requires JAVA_HOME.
        String java_home = System.getenv("JAVA_HOME");
        if (java_home != null && !java_home.isBlank()) {
            env.put("JAVA_HOME", java_home);
        }
        if (env_overrides != null) {
            env.putAll(env_overrides);
        }
        pb.redirectErrorStream(false);

        Process p = pb.start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(UTF_8));
            p.getOutputStream().close();
        } else {
            p.getOutputStream().close();
        }
        boolean done = p.waitFor(2, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            throw new AssertionError("jclaude timed out");
        }
        return new Result(
                p.exitValue(),
                new String(p.getInputStream().readAllBytes(), UTF_8),
                new String(p.getErrorStream().readAllBytes(), UTF_8));
    }

    private static Path locate_binary() {
        String override = System.getenv("JCLAUDE_BIN");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path module_relative =
                Paths.get("build", "install", "jclaude", "bin", "jclaude").toAbsolutePath();
        if (Files.isExecutable(module_relative)) {
            return module_relative;
        }
        Path repo_relative = Paths.get("jclaude-cli", "build", "install", "jclaude", "bin", "jclaude")
                .toAbsolutePath();
        if (Files.isExecutable(repo_relative)) {
            return repo_relative;
        }
        throw new IllegalStateException("jclaude binary not found at " + module_relative + " or " + repo_relative
                + " - run `./gradlew :jclaude-cli:installDist` first or set JCLAUDE_BIN.");
    }

    /** Convenience factory for the empty env-override map. */
    public static Map<String, String> emptyEnv() {
        return new HashMap<>();
    }
}
