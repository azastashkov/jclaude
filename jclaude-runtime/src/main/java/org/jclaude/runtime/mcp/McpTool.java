package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * MCP tool descriptor as returned by {@code tools/list}. Mirrors the Rust {@code McpTool} struct.
 * Field names use camelCase wire form; {@link #input_schema} is annotated to map to
 * {@code inputSchema}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpTool(
        String name,
        Optional<String> description,
        @JsonProperty("inputSchema") Optional<JsonNode> input_schema,
        Optional<JsonNode> annotations,
        @JsonProperty("_meta") Optional<JsonNode> meta) {

    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        description = description == null ? Optional.empty() : description;
        input_schema = input_schema == null ? Optional.empty() : input_schema;
        annotations = annotations == null ? Optional.empty() : annotations;
        meta = meta == null ? Optional.empty() : meta;
    }

    public static McpTool of(String name, String description, JsonNode input_schema) {
        return new McpTool(
                name,
                Optional.ofNullable(description),
                Optional.ofNullable(input_schema),
                Optional.empty(),
                Optional.empty());
    }
}
