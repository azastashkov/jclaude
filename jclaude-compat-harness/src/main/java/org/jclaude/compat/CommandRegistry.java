package org.jclaude.compat;

import java.util.List;

/** Ordered list of {@link CommandManifestEntry}, mirroring the Rust struct. */
public record CommandRegistry(List<CommandManifestEntry> entries) {

    public CommandRegistry {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static CommandRegistry empty() {
        return new CommandRegistry(List.of());
    }
}
