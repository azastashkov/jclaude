package org.jclaude.runtime.conversation;

/** Details about automatic session compaction applied during a turn. */
public record AutoCompactionEvent(int removed_message_count) {}
