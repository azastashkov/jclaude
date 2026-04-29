package org.jclaude.runtime.conversation;

import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.usage.TokenUsage;

/** Summary of one completed runtime turn, including tool results and usage. */
public record TurnSummary(
        List<ConversationMessage> assistant_messages,
        List<ConversationMessage> tool_results,
        List<PromptCacheEvent> prompt_cache_events,
        int iterations,
        TokenUsage usage,
        Optional<AutoCompactionEvent> auto_compaction) {

    public TurnSummary {
        assistant_messages = List.copyOf(assistant_messages);
        tool_results = List.copyOf(tool_results);
        prompt_cache_events = List.copyOf(prompt_cache_events);
    }
}
