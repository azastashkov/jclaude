package org.jclaude.api.providers;

/**
 * Top-level provider routing classification used by the model registry and
 * provider client dispatcher. Mirrors the Rust {@code ProviderKind} enum.
 */
public enum ProviderKind {
    ANTHROPIC,
    XAI,
    OPENAI
}
