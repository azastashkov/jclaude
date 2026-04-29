package org.jclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Strategy interface for executing a named tool with a JSON input payload. The
 * {@link org.jclaude.runtime.conversation} package may later expose its own copy of
 * this interface; until then the conversation runtime can depend on this contract directly.
 *
 * <p>Implementations must not throw — translate any failure into a {@link ToolResult} with
 * {@code is_error == true}.
 */
@FunctionalInterface
public interface ToolExecutor {

    ToolResult execute(String tool_name, JsonNode tool_input);
}
