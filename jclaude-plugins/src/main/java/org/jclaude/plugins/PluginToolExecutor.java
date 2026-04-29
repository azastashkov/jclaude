package org.jclaude.plugins;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes a {@link PluginTool} by piping the input JSON to its shell script. */
public final class PluginToolExecutor {

    private PluginToolExecutor() {}

    public static String execute(PluginTool tool, String input_json) throws IOException, InterruptedException {
        Path plugin_root = tool.plugin_root();
        String command = tool.definition().command();
        Path script_path = plugin_root.resolve(command).normalize();

        ProcessBuilder pb = new ProcessBuilder(script_path.toString());
        pb.directory(plugin_root.toFile());
        Map<String, String> env = pb.environment();
        env.putAll(safe_inherited_env());
        env.put("CLAWD_PLUGIN_ID", tool.plugin_id());
        env.put("CLAWD_TOOL_NAME", tool.definition().name());
        pb.redirectErrorStream(false);

        Process process = pb.start();
        process.getOutputStream().write(input_json.getBytes(UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();

        boolean done = process.waitFor(2, TimeUnit.MINUTES);
        if (!done) {
            process.destroyForcibly();
            throw new IOException("plugin tool timed out: " + tool.definition().name());
        }
        String stdout = new String(process.getInputStream().readAllBytes(), UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), UTF_8);
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IOException("plugin tool '" + tool.definition().name() + "' exited " + exit + ": " + stderr);
        }
        return stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout;
    }

    private static Map<String, String> safe_inherited_env() {
        Map<String, String> result = new HashMap<>();
        for (String key : new String[] {"PATH", "HOME", "JAVA_HOME"}) {
            String value = System.getenv(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }
}
