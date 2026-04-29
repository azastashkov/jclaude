package org.jclaude.cli.parity;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jclaude.api.json.JclaudeMappers;

/**
 * Subprocess driver for the mock-parity harness. Builds the command line, sets the
 * env exactly as the Rust harness does, runs the {@code jclaude} CLI, and parses
 * the JSON result line out of stdout.
 */
public final class HarnessRunner {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();
    private static final String SCENARIO_PREFIX = "PARITY_SCENARIO:";

    private HarnessRunner() {}

    public static ScenarioRun run(ScenarioCase scenario_case, HarnessWorkspace workspace, String mock_base_url)
            throws IOException, InterruptedException {
        Path binary = locate_binary();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        cmd.add("--model");
        cmd.add("sonnet");
        cmd.add("--permission-mode");
        cmd.add(scenario_case.permission_mode());
        cmd.add("--output-format=json");
        if (scenario_case.allowed_tools() != null) {
            cmd.add("--allowedTools");
            cmd.add(scenario_case.allowed_tools());
        }
        cmd.add(SCENARIO_PREFIX + scenario_case.name());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace.workspace().toFile());
        var env = pb.environment();
        env.clear();
        env.put("ANTHROPIC_API_KEY", "test-parity-key");
        env.put("ANTHROPIC_BASE_URL", mock_base_url);
        env.put("JCLAUDE_CONFIG_HOME", workspace.config_home().toString());
        env.put("HOME", workspace.home().toString());
        env.put("NO_COLOR", "1");
        env.put("PATH", "/usr/bin:/bin:/usr/local/bin");
        // JAVA_HOME is required by the generated bin/jclaude shell script.
        String java_home = System.getenv("JAVA_HOME");
        if (java_home != null && !java_home.isBlank()) {
            env.put("JAVA_HOME", java_home);
        }
        pb.redirectErrorStream(false);

        Process process = pb.start();
        if (scenario_case.stdin() != null) {
            process.getOutputStream().write(scenario_case.stdin().getBytes(UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
        } else {
            process.getOutputStream().close();
        }

        boolean done = process.waitFor(2, TimeUnit.MINUTES);
        if (!done) {
            process.destroyForcibly();
            throw new AssertionError("jclaude timed out for scenario " + scenario_case.name());
        }

        String stdout = new String(process.getInputStream().readAllBytes(), UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), UTF_8);
        int exit = process.exitValue();
        if (exit != 0) {
            throw new AssertionError("jclaude failed for scenario " + scenario_case.name() + " (exit=" + exit + ")\n"
                    + "stdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
        JsonNode response = parse_json_output(stdout);
        return new ScenarioRun(response, stdout, stderr, exit);
    }

    /**
     * Extract the result JSON from stdout. Mirrors the Rust harness's strategy: prefer the latest line
     * starting with {@code {"auto_compaction"}; otherwise scan from bottom for the last line that's a
     * complete JSON object.
     */
    public static JsonNode parse_json_output(String stdout) {
        int auto_compact_index = stdout.lastIndexOf("{\"auto_compaction\"");
        if (auto_compact_index >= 0) {
            try {
                return MAPPER.readTree(stdout.substring(auto_compact_index));
            } catch (IOException error) {
                throw new AssertionError(
                        "failed to parse auto_compaction JSON response from stdout: " + error.getMessage() + "\n"
                                + stdout,
                        error);
            }
        }
        String[] lines = stdout.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return MAPPER.readTree(trimmed);
                } catch (IOException ignored) {
                    // Try the next earlier line.
                }
            }
        }
        throw new AssertionError("no JSON response line found in stdout:\n" + stdout);
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
                + " — run `./gradlew :jclaude-cli:installDist` first or set JCLAUDE_BIN.");
    }
}
