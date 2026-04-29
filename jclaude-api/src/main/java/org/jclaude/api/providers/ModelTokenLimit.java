package org.jclaude.api.providers;

/**
 * Per-model output and context-window token caps returned by
 * {@link Providers#model_token_limit(String)}. Mirrors the Rust
 * {@code ModelTokenLimit} record.
 */
public record ModelTokenLimit(long max_output_tokens, long context_window_tokens) {}
