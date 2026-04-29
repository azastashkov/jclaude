package org.jclaude.plugins;

/** Slash command entry declared in plugin.json's {@code commands[*]} array. */
public record PluginCommandManifest(String name, String description, String command) {}
