package org.jclaude.compat;

/** A single command manifest entry: symbol name and its declaration source. */
public record CommandManifestEntry(String name, CommandSource source) {}
