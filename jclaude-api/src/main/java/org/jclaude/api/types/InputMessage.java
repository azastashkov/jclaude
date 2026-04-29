package org.jclaude.api.types;

import java.util.List;

public record InputMessage(String role, List<InputContentBlock> content) {

    public static InputMessage user_text(String text) {
        return new InputMessage("user", List.of(new InputContentBlock.Text(text)));
    }

    public static InputMessage user_tool_result(String tool_use_id, String content, boolean is_error) {
        return new InputMessage(
                "user",
                List.of(new InputContentBlock.ToolResult(
                        tool_use_id, List.of(new ToolResultContentBlock.Text(content)), is_error)));
    }

    public static InputMessage assistant(List<InputContentBlock> content) {
        return new InputMessage("assistant", content);
    }
}
