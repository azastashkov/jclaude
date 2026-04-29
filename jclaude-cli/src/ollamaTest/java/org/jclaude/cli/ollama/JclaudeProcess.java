package org.jclaude.cli.ollama;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JclaudeProcess {

    public record Result(int exitCode, String stdout, String stderr) {}

    private JclaudeProcess() {}

    public static Result run(Path cwd, Map<String, String> extraEnv, String stdin, String... args) throws Exception {
        Path binary = locate_binary();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("OPENAI_BASE_URL", AbstractOllamaTest.BASE_URL);
        env.put("OPENAI_API_KEY", "ollama");
        env.put("PATH", "/usr/bin:/bin:/usr/local/bin");
        Path home = cwd.resolve("home");
        Path config = cwd.resolve("config-home");
        Files.createDirectories(home);
        Files.createDirectories(config);
        env.put("HOME", home.toString());
        env.put("JCLAUDE_CONFIG_HOME", config.toString());
        env.put("NO_COLOR", "1");
        if (extraEnv != null) env.putAll(extraEnv);
        pb.redirectErrorStream(false);

        Process p = pb.start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(UTF_8));
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
        if (override != null && !override.isBlank()) return Paths.get(override);
        // Default: build/install/jclaude/bin/jclaude relative to the jclaude-cli module.
        // Test classes run from the module dir, so build/install/jclaude/bin/jclaude is the standard path.
        Path candidate =
                Paths.get("build", "install", "jclaude", "bin", "jclaude").toAbsolutePath();
        if (Files.isExecutable(candidate)) return candidate;
        // Fall back to project-rooted path when running from repo root.
        Path repoRoot = Paths.get("jclaude-cli", "build", "install", "jclaude", "bin", "jclaude")
                .toAbsolutePath();
        if (Files.isExecutable(repoRoot)) return repoRoot;
        throw new IllegalStateException("jclaude binary not found at " + candidate + " or " + repoRoot
                + " — run `./gradlew :jclaude-cli:installDist` first or set JCLAUDE_BIN.");
    }

    public static Map<String, String> emptyEnv() {
        return new HashMap<>();
    }
}
