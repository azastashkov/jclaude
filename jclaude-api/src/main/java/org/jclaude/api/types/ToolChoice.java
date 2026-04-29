package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolChoice.Auto.class, name = "auto"),
    @JsonSubTypes.Type(value = ToolChoice.Any.class, name = "any"),
    @JsonSubTypes.Type(value = ToolChoice.Tool.class, name = "tool")
})
public sealed interface ToolChoice permits ToolChoice.Auto, ToolChoice.Any, ToolChoice.Tool {

    @JsonTypeName("auto")
    record Auto() implements ToolChoice {}

    @JsonTypeName("any")
    record Any() implements ToolChoice {}

    @JsonTypeName("tool")
    record Tool(String name) implements ToolChoice {}

    static ToolChoice auto() {
        return new Auto();
    }

    static ToolChoice any() {
        return new Any();
    }

    static ToolChoice tool(String name) {
        return new Tool(name);
    }
}
