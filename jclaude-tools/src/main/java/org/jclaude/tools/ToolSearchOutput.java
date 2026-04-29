package org.jclaude.tools;

import java.util.List;

/**
 * Response payload for the {@code ToolSearch} tool — a flat list of tool specs that match the
 * caller's query. Phase 1 implementation does substring matching against the registry built by
 * {@link MvpToolSpecs#mvp_tool_specs()}.
 */
public record ToolSearchOutput(List<ToolSpec> matches) {

    public ToolSearchOutput {
        if (matches == null) {
            throw new IllegalArgumentException("matches must not be null");
        }
        matches = List.copyOf(matches);
    }
}
