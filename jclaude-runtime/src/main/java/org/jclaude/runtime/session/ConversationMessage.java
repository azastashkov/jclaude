package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jclaude.runtime.usage.TokenUsage;

/** One conversation message with optional token-usage metadata. */
public record ConversationMessage(
        @JsonProperty("role") MessageRole role,
        @JsonProperty("blocks") List<ContentBlock> blocks,
        @JsonProperty("usage") TokenUsage usage) {

    @JsonCreator
    public ConversationMessage {
        // Defensive copy keeps the record immutable when callers mutate the
        // list they pass in.
        blocks = List.copyOf(blocks);
    }

    public static ConversationMessage user_text(String text) {
        return new ConversationMessage(MessageRole.USER, List.of(new ContentBlock.Text(text)), null);
    }

    public static ConversationMessage assistant(List<ContentBlock> blocks) {
        return new ConversationMessage(MessageRole.ASSISTANT, blocks, null);
    }

    public static ConversationMessage assistant_with_usage(List<ContentBlock> blocks, TokenUsage usage) {
        return new ConversationMessage(MessageRole.ASSISTANT, blocks, usage);
    }

    public static ConversationMessage tool_result(
            String tool_use_id, String tool_name, String output, boolean is_error) {
        return new ConversationMessage(
                MessageRole.TOOL, List.of(new ContentBlock.ToolResult(tool_use_id, tool_name, output, is_error)), null);
    }
}
