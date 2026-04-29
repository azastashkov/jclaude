package org.jclaude.plugins;

import java.nio.file.Path;

/** Records a single plugin that failed to load (path + kind + error). */
public record PluginLoadFailure(Path plugin_root, PluginKind kind, String source, PluginError error) {

    public String message() {
        return "failed to load " + kind + " plugin from `" + plugin_root + "` (source: " + source + "): "
                + error.getMessage();
    }
}
