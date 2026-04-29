package org.jclaude.plugins;

import java.nio.file.Path;

/** Persistent record stored in {@code installed.json} per installed plugin. */
public record InstalledPluginRecord(
        PluginKind kind,
        String id,
        String name,
        String version,
        String description,
        Path install_path,
        PluginInstallSource source,
        long installed_at_unix_ms,
        long updated_at_unix_ms) {}
