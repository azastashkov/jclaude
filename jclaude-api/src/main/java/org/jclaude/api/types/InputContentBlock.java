package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InputContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = InputContentBlock.ToolUse.class, name = "tool_use"),
    @JsonSubTypes.Type(value = InputContentBlock.ToolResult.class, name = "tool_result")
})
public sealed interface InputContentBlock
        permits InputContentBlock.Text, InputContentBlock.ToolUse, InputContentBlock.ToolResult {

    @JsonTypeName("text")
    record Text(String text) implements InputContentBlock {}

    @JsonTypeName("tool_use")
    record ToolUse(String id, String name, JsonNode input) implements InputContentBlock {}

    @JsonTypeName("tool_result")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    record ToolResult(String tool_use_id, List<ToolResultContentBlock> content, boolean is_error)
            implements InputContentBlock {

        public ToolResult(String tool_use_id, List<ToolResultContentBlock> content) {
            this(tool_use_id, content, false);
        }
    }

    static InputContentBlock text(String t) {
        return new Text(t);
    }

    static InputContentBlock toolUse(String id, String name, JsonNode input) {
        return new ToolUse(id, name, input);
    }

    static InputContentBlock toolResult(String toolUseId, String content, boolean isError) {
        return new ToolResult(toolUseId, List.of(new ToolResultContentBlock.Text(content)), isError);
    }
}
