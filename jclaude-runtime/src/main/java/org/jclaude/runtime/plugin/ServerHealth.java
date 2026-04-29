package org.jclaude.runtime.plugin;

import java.util.List;
import java.util.Optional;

/** Health snapshot for a single MCP server. */
public record ServerHealth(
        String server_name, ServerStatus status, List<String> capabilities, Optional<String> last_error) {

    public ServerHealth {
        capabilities = List.copyOf(capabilities);
        last_error = last_error == null ? Optional.empty() : last_error;
    }
}
