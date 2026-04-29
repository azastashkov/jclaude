package org.jclaude.tools;

/**
 * Outcome of a single tool invocation. The {@code output} string is the JSON-encoded payload
 * the model will see; {@code is_error} corresponds to the Anthropic {@code tool_result.is_error}
 * field that signals the model that the tool failed.
 *
 * <p>Mirrors the {@code Result<String, String>} return type of the upstream Rust
 * {@code execute_tool} function — success values become {@code (output, false)} and error values
 * become {@code (message, true)}.
 */
public record ToolResult(String output, boolean is_error) {

    public ToolResult {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
    }

    /** Convenience factory for a successful (non-error) text payload. */
    public static ToolResult text(String output) {
        return new ToolResult(output, false);
    }

    /** Convenience factory for an error payload. */
    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
