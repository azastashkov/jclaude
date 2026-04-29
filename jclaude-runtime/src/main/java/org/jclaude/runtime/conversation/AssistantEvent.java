package org.jclaude.runtime.conversation;

import org.jclaude.runtime.usage.TokenUsage;

/** Streamed events emitted while processing a single assistant turn. */
public sealed interface AssistantEvent {

    /** A chunk of generated assistant text. */
    record TextDelta(String text) implements AssistantEvent {}

    /** A complete tool-use block decoded from the stream. */
    record ToolUse(String id, String name, String input) implements AssistantEvent {}

    /** Token usage reported for the assistant turn. */
    record Usage(TokenUsage usage) implements AssistantEvent {}

    /** Prompt-cache instrumentation reported by the provider. */
    record PromptCache(PromptCacheEvent event) implements AssistantEvent {}

    /** End-of-message marker. */
    record MessageStop() implements AssistantEvent {

        public static final MessageStop INSTANCE = new MessageStop();
    }
}
