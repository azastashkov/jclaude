package org.jclaude.plugins;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Materialized external plugin tool entry — one row per tool listed in {@code plugin.json}. Carries
 * both the legacy minimal triple ({@code plugin_id} + {@code plugin_root} + {@link
 * PluginToolDefinition}) used by the Phase 2 settings.json loader, and the richer Rust-shaped
 * fields ({@code plugin_name}, {@code args}, typed required permission) populated by the full
 * {@link PluginManager} pipeline.
 */
public final class PluginTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String plugin_id;
    private final String plugin_name;
    private final Path plugin_root;
    private final PluginToolDefinition definition;
    private final List<String> args;
    private final PluginToolPermission required_permission;

    /** Legacy 3-arg constructor used by {@link PluginRegistry#load_from_settings(JsonNode)}. */
    public PluginTool(String plugin_id, Path plugin_root, PluginToolDefinition definition) {
        this(
                plugin_id,
                plugin_id,
                plugin_root,
                definition,
                List.of(),
                parse_permission(definition == null ? null : definition.required_permission()));
    }

    /** Full constructor used by the rich {@link PluginManager} loader. */
    public PluginTool(
            String plugin_id,
            String plugin_name,
            Path plugin_root,
            PluginToolDefinition definition,
            List<String> args,
            PluginToolPermission required_permission) {
        this.plugin_id = Objects.requireNonNull(plugin_id, "plugin_id");
        this.plugin_name = plugin_name == null ? plugin_id : plugin_name;
        this.plugin_root = plugin_root;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.args = args == null ? List.of() : List.copyOf(args);
        this.required_permission =
                required_permission == null ? PluginToolPermission.DANGER_FULL_ACCESS : required_permission;
    }

    public String plugin_id() {
        return plugin_id;
    }

    public String plugin_name() {
        return plugin_name;
    }

    public Path plugin_root() {
        return plugin_root;
    }

    public PluginToolDefinition definition() {
        return definition;
    }

    public List<String> args() {
        return args;
    }

    public PluginToolPermission required_permission_typed() {
        return required_permission;
    }

    public String required_permission() {
        return required_permission.as_str();
    }

    /**
     * Executes the tool with the given JSON input as the Rust port did: spawns the {@code command}
     * (with optional args), pipes the input on stdin, and returns trimmed stdout. Mirrors
     * {@code PluginTool::execute} semantics.
     */
    public String execute(JsonNode input) throws PluginError {
        String input_json;
        try {
            input_json = MAPPER.writeValueAsString(input);
        } catch (Exception e) {
            throw PluginError.json(e);
        }
        String command = definition.command();
        if (command == null || command.isBlank()) {
            throw PluginError.invalid_manifest("plugin tool `" + definition.name() + "` is missing a command");
        }
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (plugin_root != null) {
            pb.directory(plugin_root.toFile());
            pb.environment()
                    .put("CLAWD_PLUGIN_ROOT", plugin_root.toAbsolutePath().toString());
        }
        pb.environment().put("CLAWD_PLUGIN_ID", plugin_id);
        pb.environment().put("CLAWD_PLUGIN_NAME", plugin_name);
        pb.environment().put("CLAWD_TOOL_NAME", definition.name());
        pb.environment().put("CLAWD_TOOL_INPUT", input_json);
        try {
            Process process = pb.start();
            process.getOutputStream().write(input_json.getBytes(UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
            int exit = process.waitFor();
            String stdout = new String(process.getInputStream().readAllBytes(), UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), UTF_8);
            if (exit == 0) {
                return strip_trailing_newline(stdout).trim();
            }
            String detail = stderr.isBlank() ? ("exit status " + exit) : stderr.trim();
            throw PluginError.command_failed("plugin tool `" + definition.name() + "` from `" + plugin_id
                    + "` failed for `" + command + "`: " + detail);
        } catch (IOException e) {
            throw PluginError.io(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PluginError.io(new IOException("interrupted while running plugin tool", e));
        }
    }

    private static String strip_trailing_newline(String s) {
        if (s.endsWith("\n")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static PluginToolPermission parse_permission(String label) {
        if (label == null) {
            return PluginToolPermission.DANGER_FULL_ACCESS;
        }
        Optional<PluginToolPermission> parsed = PluginToolPermission.parse(label);
        return parsed.orElse(PluginToolPermission.DANGER_FULL_ACCESS);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginTool that)) return false;
        return Objects.equals(plugin_id, that.plugin_id)
                && Objects.equals(plugin_name, that.plugin_name)
                && Objects.equals(plugin_root, that.plugin_root)
                && Objects.equals(definition, that.definition)
                && Objects.equals(args, that.args)
                && required_permission == that.required_permission;
    }

    @Override
    public int hashCode() {
        return Objects.hash(plugin_id, plugin_name, plugin_root, definition, args, required_permission);
    }

    @Override
    public String toString() {
        return "PluginTool[plugin_id=" + plugin_id + ", definition=" + definition.name() + "]";
    }
}
