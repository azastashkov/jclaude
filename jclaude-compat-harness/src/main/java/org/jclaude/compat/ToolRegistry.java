package org.jclaude.compat;

import java.util.List;

/** Ordered list of {@link ToolManifestEntry}, mirroring the Rust struct. */
public record ToolRegistry(List<ToolManifestEntry> entries) {

    public ToolRegistry {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static ToolRegistry empty() {
        return new ToolRegistry(List.of());
    }
}
