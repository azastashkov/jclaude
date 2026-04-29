package org.jclaude.runtime.conversation;

/**
 * Trait implemented by tool dispatchers that execute model-requested tools.
 *
 * <p>The {@code input} argument is the raw JSON string provided by the assistant
 * turn — implementations are responsible for parsing it. {@link ToolError} is
 * thrown when execution fails; the runtime captures the error message and
 * forwards it to the assistant as an error tool result.
 */
@FunctionalInterface
public interface ToolExecutor {

    String execute(String tool_name, String input) throws ToolError;
}
