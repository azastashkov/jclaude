package org.jclaude.plugins;

import java.util.List;

/** A {@link PluginDefinition} paired with its enabled flag, as held by {@link PluginRegistry}. */
public record RegisteredPlugin(PluginDefinition definition, boolean enabled) {

    public PluginMetadata metadata() {
        return definition.metadata();
    }

    public PluginHooks hooks() {
        return definition.hooks();
    }

    public List<PluginTool> tools() {
        return definition.tools();
    }

    public boolean is_enabled() {
        return enabled;
    }

    public void validate() throws PluginError {
        definition.validate();
    }

    public PluginSummary summary() {
        return new PluginSummary(metadata(), enabled);
    }
}
