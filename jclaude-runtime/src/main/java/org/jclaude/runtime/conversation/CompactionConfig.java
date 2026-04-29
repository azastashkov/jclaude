package org.jclaude.runtime.conversation;

/** Thresholds controlling when and how a session is compacted. */
public record CompactionConfig(int preserve_recent_messages, int max_estimated_tokens) {

    public static final int DEFAULT_PRESERVE_RECENT_MESSAGES = 4;
    public static final int DEFAULT_MAX_ESTIMATED_TOKENS = 10_000;

    public static CompactionConfig defaults() {
        return new CompactionConfig(DEFAULT_PRESERVE_RECENT_MESSAGES, DEFAULT_MAX_ESTIMATED_TOKENS);
    }
}
