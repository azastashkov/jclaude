package org.jclaude.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs each plugin's pre/post tool hook commands in order. Mirrors Rust's {@code HookRunner}: an
 * exit code of 0 = allow, 2 = deny, anything else = failure. Hooks pipe a JSON payload describing
 * the tool invocation on stdin.
 */
public final class HookRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginHooks hooks;

    public HookRunner(PluginHooks hooks) {
        this.hooks = hooks == null ? PluginHooks.empty() : hooks;
    }

    public static HookRunner from_registry(PluginRegistry plugin_registry) throws PluginError {
        return new HookRunner(plugin_registry.aggregated_hooks());
    }

    public PluginHooks hooks() {
        return hooks;
    }

    public HookRunResult run_pre_tool_use(String tool_name, String tool_input) {
        return run_commands(HookEvent.PRE_TOOL_USE, hooks.pre_tool_use(), tool_name, tool_input, null, false);
    }

    public HookRunResult run_post_tool_use(String tool_name, String tool_input, String tool_output, boolean is_error) {
        return run_commands(
                HookEvent.POST_TOOL_USE, hooks.post_tool_use(), tool_name, tool_input, tool_output, is_error);
    }

    public HookRunResult run_post_tool_use_failure(String tool_name, String tool_input, String tool_error) {
        return run_commands(
                HookEvent.POST_TOOL_USE_FAILURE,
                hooks.post_tool_use_failure(),
                tool_name,
                tool_input,
                tool_error,
                true);
    }

    private static HookRunResult run_commands(
            HookEvent event,
            List<String> commands,
            String tool_name,
            String tool_input,
            String tool_output,
            boolean is_error) {
        if (commands.isEmpty()) {
            return HookRunResult.allow(List.of());
        }
        String payload = render_payload(event, tool_name, tool_input, tool_output, is_error);
        List<String> messages = new ArrayList<>();
        for (String command : commands) {
            HookCommandOutcome outcome =
                    run_single_command(command, event, tool_name, tool_input, tool_output, is_error, payload);
            switch (outcome) {
                case HookCommandOutcome.Allow allow -> {
                    if (allow.message() != null && !allow.message().isEmpty()) {
                        messages.add(allow.message());
                    }
                }
                case HookCommandOutcome.Deny deny -> {
                    String msg = deny.message();
                    messages.add(
                            msg == null || msg.isEmpty()
                                    ? event.as_str() + " hook denied tool `" + tool_name + "`"
                                    : msg);
                    return new HookRunResult(true, false, messages);
                }
                case HookCommandOutcome.Failed failed -> {
                    messages.add(failed.message());
                    return new HookRunResult(false, true, messages);
                }
            }
        }
        return HookRunResult.allow(messages);
    }

    private static HookCommandOutcome run_single_command(
            String command,
            HookEvent event,
            String tool_name,
            String tool_input,
            String tool_output,
            boolean is_error,
            String payload) {
        ProcessBuilder pb = shell_command(command);
        pb.environment().put("HOOK_EVENT", event.as_str());
        pb.environment().put("HOOK_TOOL_NAME", tool_name);
        pb.environment().put("HOOK_TOOL_INPUT", tool_input);
        pb.environment().put("HOOK_TOOL_IS_ERROR", is_error ? "1" : "0");
        if (tool_output != null) {
            pb.environment().put("HOOK_TOOL_OUTPUT", tool_output);
        }
        try {
            Process process = pb.start();
            try (OutputStream os = process.getOutputStream()) {
                try {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException broken) {
                    // tolerate broken pipe — child may exit before reading stdin
                }
            } catch (IOException ignored) {
                // best-effort close
            }
            int exit = process.waitFor();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String message = stdout.isEmpty() ? null : stdout;
            return switch (exit) {
                case 0 -> new HookCommandOutcome.Allow(message);
                case 2 -> new HookCommandOutcome.Deny(message);
                default -> new HookCommandOutcome.Failed(format_warning(command, exit, message, stderr));
            };
        } catch (IOException e) {
            return new HookCommandOutcome.Failed(event.as_str() + " hook `" + command + "` failed to start for `"
                    + tool_name + "`: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookCommandOutcome.Failed(
                    event.as_str() + " hook `" + command + "` interrupted while handling `" + tool_name + "`");
        }
    }

    private static ProcessBuilder shell_command(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        if (windows) {
            return new ProcessBuilder("cmd", "/C", command);
        }
        if (Files.exists(Paths.get(command))) {
            return new ProcessBuilder("sh", command);
        }
        return new ProcessBuilder("sh", "-lc", command);
    }

    private static String format_warning(String command, int code, String stdout, String stderr) {
        StringBuilder sb = new StringBuilder("Hook `")
                .append(command)
                .append("` exited with status ")
                .append(code);
        if (stdout != null && !stdout.isEmpty()) {
            sb.append(": ").append(stdout);
        } else if (!stderr.isEmpty()) {
            sb.append(": ").append(stderr);
        }
        return sb.toString();
    }

    private static String render_payload(
            HookEvent event, String tool_name, String tool_input, String tool_output, boolean is_error) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("hook_event_name", event.as_str());
        node.put("tool_name", tool_name);
        node.set("tool_input", parse_tool_input(tool_input));
        node.put("tool_input_json", tool_input);
        if (event == HookEvent.POST_TOOL_USE_FAILURE) {
            if (tool_output == null) {
                node.putNull("tool_error");
            } else {
                node.put("tool_error", tool_output);
            }
            node.put("tool_result_is_error", true);
        } else {
            if (tool_output == null) {
                node.putNull("tool_output");
            } else {
                node.put("tool_output", tool_output);
            }
            node.put("tool_result_is_error", is_error);
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode parse_tool_input(String tool_input) {
        try {
            return MAPPER.readTree(tool_input);
        } catch (Exception e) {
            ObjectNode raw = MAPPER.createObjectNode();
            raw.put("raw", tool_input);
            return raw;
        }
    }

    /** Inner outcome family used while iterating hook commands. */
    private sealed interface HookCommandOutcome {
        record Allow(String message) implements HookCommandOutcome {}

        record Deny(String message) implements HookCommandOutcome {}

        record Failed(String message) implements HookCommandOutcome {}
    }
}
