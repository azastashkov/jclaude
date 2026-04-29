package org.jclaude.plugins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Configuration bag for {@link PluginManager}, equivalent to Rust's {@code PluginManagerConfig}. */
public final class PluginManagerConfig {

    private final Path config_home;
    private final Map<String, Boolean> enabled_plugins;
    private final List<Path> external_dirs;
    private Optional<Path> install_root;
    private Optional<Path> registry_path;
    private Optional<Path> bundled_root;

    public PluginManagerConfig(Path config_home) {
        this.config_home = config_home;
        this.enabled_plugins = new TreeMap<>();
        this.external_dirs = new ArrayList<>();
        this.install_root = Optional.empty();
        this.registry_path = Optional.empty();
        this.bundled_root = Optional.empty();
    }

    public Path config_home() {
        return config_home;
    }

    public Map<String, Boolean> enabled_plugins() {
        return enabled_plugins;
    }

    public List<Path> external_dirs() {
        return external_dirs;
    }

    public Optional<Path> install_root() {
        return install_root;
    }

    public PluginManagerConfig with_install_root(Path path) {
        this.install_root = Optional.ofNullable(path);
        return this;
    }

    public Optional<Path> registry_path() {
        return registry_path;
    }

    public PluginManagerConfig with_registry_path(Path path) {
        this.registry_path = Optional.ofNullable(path);
        return this;
    }

    public Optional<Path> bundled_root() {
        return bundled_root;
    }

    public PluginManagerConfig with_bundled_root(Path path) {
        this.bundled_root = Optional.ofNullable(path);
        return this;
    }

    public PluginManagerConfig with_external_dirs(List<Path> dirs) {
        this.external_dirs.clear();
        if (dirs != null) {
            this.external_dirs.addAll(dirs);
        }
        return this;
    }

    public PluginManagerConfig with_enabled(Map<String, Boolean> map) {
        this.enabled_plugins.clear();
        if (map != null) {
            this.enabled_plugins.putAll(map);
        }
        return this;
    }
}
