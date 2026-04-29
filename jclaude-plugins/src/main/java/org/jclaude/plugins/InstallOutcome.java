package org.jclaude.plugins;

import java.nio.file.Path;

/** Result of a successful {@link PluginManager#install} call. */
public record InstallOutcome(String plugin_id, String version, Path install_path) {}
