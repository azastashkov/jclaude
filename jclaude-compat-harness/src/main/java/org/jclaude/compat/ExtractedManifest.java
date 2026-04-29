package org.jclaude.compat;

/** Bundle of upstream parsing outputs returned by {@link CompatHarness#extract_manifest}. */
public record ExtractedManifest(CommandRegistry commands, ToolRegistry tools, BootstrapPlan bootstrap) {}
