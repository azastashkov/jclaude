package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OutputContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = OutputContentBlock.ToolUse.class, name = "tool_use"),
    @JsonSubTypes.Type(value = OutputContentBlock.Thinking.class, name = "thinking"),
    @JsonSubTypes.Type(value = OutputContentBlock.RedactedThinking.class, name = "redacted_thinking")
})
public sealed interface OutputContentBlock
        permits OutputContentBlock.Text,
                OutputContentBlock.ToolUse,
                OutputContentBlock.Thinking,
                OutputContentBlock.RedactedThinking {

    @JsonTypeName("text")
    record Text(String text) implements OutputContentBlock {}

    @JsonTypeName("tool_use")
    record ToolUse(String id, String name, JsonNode input) implements OutputContentBlock {}

    @JsonTypeName("thinking")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Thinking(String thinking, String signature) implements OutputContentBlock {

        public Thinking(String thinking) {
            this(thinking, null);
        }
    }

    @JsonTypeName("redacted_thinking")
    record RedactedThinking(JsonNode data) implements OutputContentBlock {}
}
