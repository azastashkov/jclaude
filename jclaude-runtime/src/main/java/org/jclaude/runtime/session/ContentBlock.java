package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Structured message content stored inside a {@link Session}.
 *
 * <p>This is the SESSION-side variant — it intentionally differs from the API
 * input content block. The {@code ToolUse} variant carries {@code input} as a
 * raw {@link String} (the JSON serialization of the tool input), matching the
 * Rust source.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result")
})
public sealed interface ContentBlock {

    @JsonTypeName("text")
    record Text(@JsonProperty("text") String text) implements ContentBlock {}

    @JsonTypeName("tool_use")
    record ToolUse(
            @JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("input") String input)
            implements ContentBlock {}

    @JsonTypeName("tool_result")
    record ToolResult(
            @JsonProperty("tool_use_id") String tool_use_id,
            @JsonProperty("tool_name") String tool_name,
            @JsonProperty("output") String output,
            @JsonProperty("is_error") boolean is_error)
            implements ContentBlock {}
}
