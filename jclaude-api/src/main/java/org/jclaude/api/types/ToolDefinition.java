package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(String name, String description, JsonNode input_schema) {

    public ToolDefinition(String name, JsonNode input_schema) {
        this(name, null, input_schema);
    }
}
