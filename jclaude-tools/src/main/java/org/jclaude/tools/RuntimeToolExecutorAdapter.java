package org.jclaude.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.conversation.ToolError;

/**
 * Adapter that exposes a {@link ToolDispatcher} (which speaks {@code (String name, JsonNode
 * input)}) as a {@link org.jclaude.runtime.conversation.ToolExecutor} (which speaks
 * {@code (String name, String input) -> String, throws ToolError}). Wires the JSON parsing on the
 * way in and converts {@code is_error} results into thrown {@link ToolError}s on the way out.
 */
public final class RuntimeToolExecutorAdapter implements org.jclaude.runtime.conversation.ToolExecutor {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final ToolDispatcher dispatcher;

    public RuntimeToolExecutorAdapter(ToolDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new IllegalArgumentException("dispatcher must not be null");
        }
        this.dispatcher = dispatcher;
    }

    @Override
    public String execute(String tool_name, String input) throws ToolError {
        JsonNode parsed;
        try {
            parsed = (input == null || input.isBlank()) ? MAPPER.createObjectNode() : MAPPER.readTree(input);
        } catch (JsonProcessingException e) {
            throw new ToolError("invalid tool input JSON for '" + tool_name + "': " + e.getOriginalMessage());
        }
        ToolResult result = dispatcher.execute(tool_name, parsed);
        if (result.is_error()) {
            throw new ToolError(result.output());
        }
        return result.output();
    }
}
