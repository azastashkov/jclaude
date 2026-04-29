package org.jclaude.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Tool entry declared in plugin.json's {@code tools[*]} array. */
public record PluginToolManifest(
        String name,
        String description,
        JsonNode input_schema,
        String command,
        List<String> args,
        PluginToolPermission required_permission) {

    public PluginToolManifest {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
