package org.jclaude.runtime.conversation;

import org.jclaude.runtime.session.Session;

/** Result of compacting a session into a summary plus preserved tail messages. */
public record CompactionResult(
        String summary, String formatted_summary, Session compacted_session, int removed_message_count) {}
