package org.jclaude.compat;

/** A single tool manifest entry: symbol name and its declaration source. */
public record ToolManifestEntry(String name, ToolSource source) {}
