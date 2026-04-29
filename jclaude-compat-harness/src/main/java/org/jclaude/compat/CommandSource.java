package org.jclaude.compat;

/** Origin classification for a command symbol parsed from upstream {@code commands.ts}. */
public enum CommandSource {
    BUILTIN,
    INTERNAL_ONLY,
    FEATURE_GATED
}
