package org.jclaude.runtime.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plugin-related settings. */
public record RuntimePluginConfig(
        Map<String, Boolean> enabled_plugins,
        List<String> external_directories,
        Optional<String> install_root,
        Optional<String> registry_path,
        Optional<String> bundled_root,
        Optional<Integer> max_output_tokens) {

    public RuntimePluginConfig {
        enabled_plugins = Map.copyOf(enabled_plugins);
        external_directories = List.copyOf(external_directories);
        install_root = install_root == null ? Optional.empty() : install_root;
        registry_path = registry_path == null ? Optional.empty() : registry_path;
        bundled_root = bundled_root == null ? Optional.empty() : bundled_root;
        max_output_tokens = max_output_tokens == null ? Optional.empty() : max_output_tokens;
    }

    public static RuntimePluginConfig empty() {
        return new RuntimePluginConfig(
                Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
