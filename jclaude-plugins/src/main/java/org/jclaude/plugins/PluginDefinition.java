package org.jclaude.plugins;

import java.util.List;

/**
 * Sealed type representing a single plugin pulled from one of the three plugin sources (builtin
 * scaffold, bundled resource, or external/installed). All variants carry the same payload.
 */
public sealed interface PluginDefinition {

    PluginMetadata metadata();

    PluginHooks hooks();

    PluginLifecycle lifecycle();

    List<PluginTool> tools();

    record Builtin(PluginMetadata metadata, PluginHooks hooks, PluginLifecycle lifecycle, List<PluginTool> tools)
            implements PluginDefinition {
        public Builtin {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record Bundled(PluginMetadata metadata, PluginHooks hooks, PluginLifecycle lifecycle, List<PluginTool> tools)
            implements PluginDefinition {
        public Bundled {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record External(PluginMetadata metadata, PluginHooks hooks, PluginLifecycle lifecycle, List<PluginTool> tools)
            implements PluginDefinition {
        public External {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /** Validate that hook / tool / lifecycle paths still resolve on disk for non-builtin plugins. */
    default void validate() throws PluginError {
        if (this instanceof Builtin) {
            return;
        }
        java.nio.file.Path root = metadata().root().orElse(null);
        if (root == null) {
            return;
        }
        for (String entry : hooks().pre_tool_use()) {
            PluginManager.validate_command_path(root, entry, "hook");
        }
        for (String entry : hooks().post_tool_use()) {
            PluginManager.validate_command_path(root, entry, "hook");
        }
        for (String entry : hooks().post_tool_use_failure()) {
            PluginManager.validate_command_path(root, entry, "hook");
        }
        for (String entry : lifecycle().init()) {
            PluginManager.validate_command_path(root, entry, "lifecycle command");
        }
        for (String entry : lifecycle().shutdown()) {
            PluginManager.validate_command_path(root, entry, "lifecycle command");
        }
        for (PluginTool tool : tools()) {
            String cmd = tool.definition().command();
            if (cmd != null) {
                PluginManager.validate_command_path(root, cmd, "tool");
            }
        }
    }
}
