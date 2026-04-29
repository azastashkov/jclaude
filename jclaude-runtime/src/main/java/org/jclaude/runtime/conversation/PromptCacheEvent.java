package org.jclaude.runtime.conversation;

/** Prompt-cache telemetry captured from the provider response stream. */
public record PromptCacheEvent(
        boolean unexpected,
        String reason,
        long previous_cache_read_input_tokens,
        long current_cache_read_input_tokens,
        long token_drop) {}
