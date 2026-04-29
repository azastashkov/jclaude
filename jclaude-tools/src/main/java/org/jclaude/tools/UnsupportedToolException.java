package org.jclaude.tools;

/**
 * Raised by {@link ToolDispatcher} when a tool name is not registered in the MVP dispatch table.
 *
 * <p>Reserved for the 50+ tools (Agent, NotebookEdit, WebFetch, Task*, Worker*, etc.) that exist
 * in the upstream Rust dispatcher but are deferred to later phases. Callers that need graceful
 * degradation should catch this and return an error {@link ToolResult} to the model.
 */
public class UnsupportedToolException extends RuntimeException {

    private final String tool_name;

    public UnsupportedToolException(String tool_name) {
        super("unsupported tool: " + tool_name);
        this.tool_name = tool_name;
    }

    public String tool_name() {
        return tool_name;
    }
}
