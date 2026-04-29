package org.jclaude.plugins;

import java.nio.file.Path;
import java.util.Optional;

/** Stable identifying details for a plugin (id, name, version, kind, source, root path). */
public record PluginMetadata(
        String id,
        String name,
        String version,
        String description,
        PluginKind kind,
        String source,
        boolean default_enabled,
        Optional<Path> root) {

    public PluginMetadata {
        root = root == null ? Optional.empty() : root;
    }
}
