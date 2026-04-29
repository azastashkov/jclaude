package org.jclaude.plugins;

import java.util.List;

/** Init / Shutdown command lists for a plugin. */
public record PluginLifecycle(List<String> init, List<String> shutdown) {

    public PluginLifecycle {
        init = init == null ? List.of() : List.copyOf(init);
        shutdown = shutdown == null ? List.of() : List.copyOf(shutdown);
    }

    public static PluginLifecycle empty() {
        return new PluginLifecycle(List.of(), List.of());
    }

    public boolean is_empty() {
        return init.isEmpty() && shutdown.isEmpty();
    }
}
