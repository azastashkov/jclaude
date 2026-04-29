package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** MCP resource descriptor as returned by {@code resources/list}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpResource(
        String uri,
        Optional<String> name,
        Optional<String> description,
        @JsonProperty("mimeType") Optional<String> mime_type,
        Optional<JsonNode> annotations,
        @JsonProperty("_meta") Optional<JsonNode> meta) {

    public McpResource {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be null or blank");
        }
        name = name == null ? Optional.empty() : name;
        description = description == null ? Optional.empty() : description;
        mime_type = mime_type == null ? Optional.empty() : mime_type;
        annotations = annotations == null ? Optional.empty() : annotations;
        meta = meta == null ? Optional.empty() : meta;
    }
}
