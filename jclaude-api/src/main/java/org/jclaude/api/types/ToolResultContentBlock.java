package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResultContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ToolResultContentBlock.Json.class, name = "json")
})
public sealed interface ToolResultContentBlock permits ToolResultContentBlock.Text, ToolResultContentBlock.Json {

    @JsonTypeName("text")
    record Text(String text) implements ToolResultContentBlock {}

    @JsonTypeName("json")
    record Json(JsonNode value) implements ToolResultContentBlock {}

    static ToolResultContentBlock text(String t) {
        return new Text(t);
    }

    static ToolResultContentBlock json(JsonNode v) {
        return new Json(v);
    }
}
