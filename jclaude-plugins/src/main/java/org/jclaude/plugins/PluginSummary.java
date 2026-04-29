package org.jclaude.plugins;

/** Lightweight summary used by {@code list_plugins} / {@code list_installed_plugins}. */
public record PluginSummary(PluginMetadata metadata, boolean enabled) {}
