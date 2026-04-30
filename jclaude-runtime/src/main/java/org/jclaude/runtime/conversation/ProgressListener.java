package org.jclaude.runtime.conversation;

/**
 * Receives incremental signals from the streaming model adapter so the CLI can show what the model
 * is actively doing during a long turn.
 *
 * <p>The {@link ApiClient} contract is buffered (it returns a {@code List<AssistantEvent>} once the
 * whole turn is done). Without this listener the CLI cannot tell the difference between "model is
 * thinking", "model is streaming a long answer", or "model is mid-tool-call" — they all look like
 * silence. Adapters that support streaming wire each {@link
 * org.jclaude.api.types.StreamEvent} into one of the methods below as it arrives over the wire.
 *
 * <p>All methods have safe defaults so callers may implement only the ones they care about. The
 * {@link #NO_OP} singleton is used when no listener is supplied; it is intentionally cheap so the
 * non-REPL paths (one-shot {@code -p} prints, integration tests) pay zero overhead.
 */
public interface ProgressListener {

    /** No-op listener used when the caller does not care about per-chunk signals. */
    ProgressListener NO_OP = new ProgressListener() {};

    /**
     * Called as soon as the adapter knows the model is starting a tool-use block. For Anthropic /
     * structured OpenAI streams this fires on {@code content_block_start} for the tool. For
     * Hermes-XML tool calls (qwen3-coder and friends) this fires once enough text has been
     * accumulated to identify the function name.
     */
    default void on_tool_starting(String tool_name) {}

    /**
     * Called for each chunk of assistant text received from the wire. {@code char_count} is the
     * size of the just-arrived chunk, not the cumulative total — implementations should accumulate
     * if they want a running total.
     */
    default void on_text_delta_received(int char_count) {}
}
