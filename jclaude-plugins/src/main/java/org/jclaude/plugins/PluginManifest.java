package org.jclaude.plugins;

import java.util.List;

/** Validated plugin.json contents — produced by {@link PluginManager#load_plugin_from_directory}. */
public record PluginManifest(
        String name,
        String version,
        String description,
        List<PluginPermission> permissions,
        boolean default_enabled,
        PluginHooks hooks,
        PluginLifecycle lifecycle,
        List<PluginToolManifest> tools,
        List<PluginCommandManifest> commands) {

    public PluginManifest {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        hooks = hooks == null ? PluginHooks.empty() : hooks;
        lifecycle = lifecycle == null ? PluginLifecycle.empty() : lifecycle;
        tools = tools == null ? List.of() : List.copyOf(tools);
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}
