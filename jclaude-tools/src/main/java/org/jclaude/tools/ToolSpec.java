package org.jclaude.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MVP tool specification matching the Anthropic {@code ToolDefinition} shape used by
 * the Messages API. Mirrors the upstream Rust {@code ToolSpec} from
 * {@code claw-code/rust/crates/tools/src/lib.rs} (Phase 1 fields only).
 *
 * <p>Permission classification, runtime metadata, and plugin-tool fields from the upstream
 * record are deferred to later phases.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSpec(String name, String description, JsonNode input_schema) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (input_schema == null) {
            throw new IllegalArgumentException("input_schema must not be null");
        }
    }
}
