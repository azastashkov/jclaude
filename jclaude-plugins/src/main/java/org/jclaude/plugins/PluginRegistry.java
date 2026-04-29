package org.jclaude.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Plugin registry — the heart of the plugins module. Holds an ordered list of {@link
 * RegisteredPlugin}s and exposes both the rich Rust-style API (aggregated_hooks, aggregated_tools,
 * summaries, contains) and the legacy Phase 2 API (empty, tools, find, load_default,
 * load_from_settings) that earlier modules already depend on.
 */
public final class PluginRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<RegisteredPlugin> plugins;
    private final List<PluginTool> legacy_tools;

    private PluginRegistry(List<RegisteredPlugin> plugins, List<PluginTool> legacy_tools) {
        List<RegisteredPlugin> sorted = new ArrayList<>(plugins);
        sorted.sort(Comparator.comparing(p -> p.metadata().id()));
        this.plugins = List.copyOf(sorted);
        this.legacy_tools = List.copyOf(legacy_tools);
    }

    /** Builds a registry from a list of {@link RegisteredPlugin} instances (Rust-style). */
    public static PluginRegistry of(List<RegisteredPlugin> plugins) {
        return new PluginRegistry(plugins, List.of());
    }

    public static PluginRegistry empty() {
        return new PluginRegistry(List.of(), List.of());
    }

    /** All registered plugins in alphabetical order by id. */
    public List<RegisteredPlugin> plugins() {
        return plugins;
    }

    public Optional<RegisteredPlugin> get(String plugin_id) {
        for (RegisteredPlugin plugin : plugins) {
            if (plugin.metadata().id().equals(plugin_id)) {
                return Optional.of(plugin);
            }
        }
        return Optional.empty();
    }

    public boolean contains(String plugin_id) {
        return get(plugin_id).isPresent();
    }

    public List<PluginSummary> summaries() {
        List<PluginSummary> result = new ArrayList<>(plugins.size());
        for (RegisteredPlugin plugin : plugins) {
            result.add(plugin.summary());
        }
        return List.copyOf(result);
    }

    /** Aggregates all enabled plugin hook commands. Validates plugin paths first. */
    public PluginHooks aggregated_hooks() throws PluginError {
        PluginHooks acc = PluginHooks.empty();
        for (RegisteredPlugin plugin : plugins) {
            if (!plugin.is_enabled()) {
                continue;
            }
            plugin.validate();
            acc = acc.merged_with(plugin.hooks());
        }
        return acc;
    }

    /** Aggregates all enabled plugin tools, rejecting duplicate tool names across plugins. */
    public List<PluginTool> aggregated_tools() throws PluginError {
        List<PluginTool> tools = new ArrayList<>();
        Map<String, String> seen = new TreeMap<>();
        for (RegisteredPlugin plugin : plugins) {
            if (!plugin.is_enabled()) {
                continue;
            }
            plugin.validate();
            for (PluginTool tool : plugin.tools()) {
                String name = tool.definition().name();
                String existing = seen.put(name, tool.plugin_id());
                if (existing != null) {
                    throw PluginError.invalid_manifest("plugin tool `" + name + "` is defined by both `" + existing
                            + "` and `" + tool.plugin_id() + "`");
                }
                tools.add(tool);
            }
        }
        return List.copyOf(tools);
    }

    /** Runs each enabled plugin's {@code init} commands in order. */
    public void initialize() throws PluginError {
        for (RegisteredPlugin plugin : plugins) {
            if (!plugin.is_enabled()) {
                continue;
            }
            plugin.validate();
            PluginManager.run_lifecycle_commands(
                    plugin.metadata(),
                    plugin.definition().lifecycle(),
                    "init",
                    plugin.definition().lifecycle().init());
        }
    }

    /** Runs each enabled plugin's {@code shutdown} commands in reverse order. */
    public void shutdown() throws PluginError {
        for (int i = plugins.size() - 1; i >= 0; i--) {
            RegisteredPlugin plugin = plugins.get(i);
            if (!plugin.is_enabled()) {
                continue;
            }
            PluginManager.run_lifecycle_commands(
                    plugin.metadata(),
                    plugin.definition().lifecycle(),
                    "shutdown",
                    plugin.definition().lifecycle().shutdown());
        }
    }

    // ---- Legacy Phase 2 API used by jclaude-tools / jclaude-cli ----

    /** Returns the union of legacy settings.json tools and aggregated rich-API enabled tools. */
    public List<PluginTool> tools() {
        if (plugins.isEmpty()) {
            return legacy_tools;
        }
        List<PluginTool> result = new ArrayList<>(legacy_tools);
        for (RegisteredPlugin plugin : plugins) {
            if (plugin.is_enabled()) {
                result.addAll(plugin.tools());
            }
        }
        return List.copyOf(result);
    }

    public Optional<PluginTool> find(String tool_name) {
        for (PluginTool tool : tools()) {
            if (tool.definition().name().equals(tool_name)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    /** Legacy loader — reads {@code settings.json} from JCLAUDE/CLAUDE/CLAW config home. */
    public static PluginRegistry load_default() {
        Path settings_path = resolve_settings_path();
        if (settings_path == null || !Files.isRegularFile(settings_path)) {
            return empty();
        }
        try {
            JsonNode root = MAPPER.readTree(settings_path.toFile());
            return load_from_settings(root);
        } catch (IOException ignored) {
            return empty();
        }
    }

    /** Legacy loader — discovers plugin tools from a parsed settings.json tree. */
    public static PluginRegistry load_from_settings(JsonNode settings) {
        Map<String, Boolean> enabled = enabled_plugins(settings.path("enabledPlugins"));
        List<Path> external_dirs = external_directories(settings.path("plugins").path("externalDirectories"));
        if (enabled.isEmpty() || external_dirs.isEmpty()) {
            return empty();
        }
        List<PluginTool> all = new ArrayList<>();
        for (Path external_root : external_dirs) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(external_root)) {
                for (Path candidate : stream) {
                    if (!Files.isDirectory(candidate)) {
                        continue;
                    }
                    String plugin_name = candidate.getFileName().toString();
                    String plugin_id = plugin_name + "@external";
                    if (!Boolean.TRUE.equals(enabled.get(plugin_id))) {
                        continue;
                    }
                    Path manifest_path = candidate.resolve(".claude-plugin").resolve("plugin.json");
                    if (!Files.isRegularFile(manifest_path)) {
                        continue;
                    }
                    JsonNode manifest;
                    try {
                        manifest = MAPPER.readTree(manifest_path.toFile());
                    } catch (IOException ignored) {
                        continue;
                    }
                    JsonNode tools_node = manifest.path("tools");
                    if (!tools_node.isArray()) {
                        continue;
                    }
                    for (JsonNode tool_node : tools_node) {
                        PluginToolDefinition definition = PluginToolDefinition.from_json(tool_node);
                        if (definition == null) {
                            continue;
                        }
                        all.add(new PluginTool(plugin_id, candidate.toAbsolutePath(), definition));
                    }
                }
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return new PluginRegistry(List.of(), all);
    }

    private static Map<String, Boolean> enabled_plugins(JsonNode node) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!node.isObject()) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value != null && value.isBoolean()) {
                result.put(entry.getKey(), value.asBoolean());
            }
        }
        return result;
    }

    private static List<Path> external_directories(JsonNode node) {
        List<Path> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                String text = entry.asText();
                if (!text.isBlank()) {
                    result.add(Paths.get(text));
                }
            }
        }
        return result;
    }

    private static Path resolve_settings_path() {
        String override = System.getenv("JCLAUDE_CONFIG_HOME");
        if (override == null || override.isBlank()) {
            override = System.getenv("CLAUDE_CONFIG_HOME");
        }
        if (override == null || override.isBlank()) {
            override = System.getenv("CLAW_CONFIG_HOME");
        }
        if (override != null && !override.isBlank()) {
            return Paths.get(override).resolve("settings.json");
        }
        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            return Paths.get(home).resolve(".jclaude").resolve("settings.json");
        }
        return null;
    }

    /** Stream interface for ad-hoc filtering. */
    public Stream<RegisteredPlugin> stream() {
        return plugins.stream();
    }
}
