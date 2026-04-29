package org.jclaude.plugins;

import java.nio.file.Path;

/** Result of a successful {@link PluginManager#update} call. */
public record UpdateOutcome(String plugin_id, String old_version, String new_version, Path install_path) {}
