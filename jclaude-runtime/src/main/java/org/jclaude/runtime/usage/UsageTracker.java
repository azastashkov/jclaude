package org.jclaude.runtime.usage;

import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.Session;

/** Aggregates token usage across a running session. */
public final class UsageTracker {

    private TokenUsage latest_turn;
    private TokenUsage cumulative;
    private int turns;

    public UsageTracker() {
        this.latest_turn = TokenUsage.ZERO;
        this.cumulative = TokenUsage.ZERO;
        this.turns = 0;
    }

    /** Builds a tracker that absorbs every recorded usage entry from {@code session}. */
    public static UsageTracker from_session(Session session) {
        UsageTracker tracker = new UsageTracker();
        for (ConversationMessage message : session.messages()) {
            TokenUsage usage = message.usage();
            if (usage != null) {
                tracker.record(usage);
            }
        }
        return tracker;
    }

    /** Records usage for the latest turn and updates cumulative totals. */
    public void record(TokenUsage usage) {
        this.latest_turn = usage;
        this.cumulative = new TokenUsage(
                this.cumulative.input_tokens() + usage.input_tokens(),
                this.cumulative.output_tokens() + usage.output_tokens(),
                this.cumulative.cache_creation_input_tokens() + usage.cache_creation_input_tokens(),
                this.cumulative.cache_read_input_tokens() + usage.cache_read_input_tokens());
        this.turns += 1;
    }

    public TokenUsage current_turn_usage() {
        return latest_turn;
    }

    public TokenUsage cumulative_usage() {
        return cumulative;
    }

    public int turns() {
        return turns;
    }
}
