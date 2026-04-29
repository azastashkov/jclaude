package org.jclaude.runtime.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.permissions.PermissionContext;
import org.jclaude.runtime.permissions.PermissionOutcome;
import org.jclaude.runtime.permissions.PermissionPolicy;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.session.Session;
import org.jclaude.runtime.usage.TokenUsage;
import org.jclaude.runtime.usage.UsageTracker;

/** Coordinates the model loop, tool execution, and session updates. */
public final class ConversationRuntime {

    static final long DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD = 100_000L;
    static final String AUTO_COMPACTION_THRESHOLD_ENV_VAR = "CLAUDE_CODE_AUTO_COMPACT_INPUT_TOKENS";

    private Session session;
    private final ApiClient api_client;
    private final ToolExecutor tool_executor;
    private final PermissionPolicy permission_policy;
    private final List<String> system_prompt;
    private long max_iterations;
    private final UsageTracker usage_tracker;
    private long auto_compaction_input_tokens_threshold;

    public ConversationRuntime(
            Session session,
            ApiClient api_client,
            ToolExecutor tool_executor,
            PermissionPolicy permission_policy,
            List<String> system_prompt) {
        this.session = session;
        this.api_client = api_client;
        this.tool_executor = tool_executor;
        this.permission_policy = permission_policy;
        this.system_prompt = List.copyOf(system_prompt);
        this.max_iterations = Long.MAX_VALUE;
        this.usage_tracker = UsageTracker.from_session(session);
        this.auto_compaction_input_tokens_threshold = auto_compaction_threshold_from_env();
    }

    public ConversationRuntime with_max_iterations(long max_iterations) {
        this.max_iterations = max_iterations;
        return this;
    }

    public ConversationRuntime with_auto_compaction_input_tokens_threshold(long threshold) {
        this.auto_compaction_input_tokens_threshold = threshold;
        return this;
    }

    public Session session() {
        return session;
    }

    public UsageTracker usage() {
        return usage_tracker;
    }

    public Session fork_session(String branch_name) {
        return session.forked(branch_name);
    }

    /** Runs a single turn — repeatedly calls the model until no more tool uses are pending. */
    public TurnSummary run_turn(String user_input, PermissionPrompter prompter) {
        // ROADMAP #38: session-health canary — probe if context was compacted.
        if (session.compaction().isPresent()) {
            try {
                run_session_health_probe();
            } catch (RuntimeException error) {
                throw new RuntimeError("Session health probe failed after compaction: " + error.getMessage()
                        + ". The session may be in an inconsistent state. "
                        + "Consider starting a fresh session with /session new.");
            }
        }

        try {
            session.push_user_text(user_input);
        } catch (RuntimeException error) {
            throw new RuntimeError(error.getMessage(), error);
        }

        List<ConversationMessage> assistant_messages = new ArrayList<>();
        List<ConversationMessage> tool_results = new ArrayList<>();
        List<PromptCacheEvent> prompt_cache_events = new ArrayList<>();
        long iterations = 0;

        while (true) {
            iterations += 1;
            if (iterations > max_iterations) {
                throw new RuntimeError("conversation loop exceeded the maximum number of iterations");
            }

            ApiRequest request = new ApiRequest(system_prompt, session.messages());
            List<AssistantEvent> events;
            try {
                events = api_client.stream(request);
            } catch (RuntimeError error) {
                throw error;
            }

            BuildResult built = build_assistant_message(events);
            if (built.usage != null) {
                usage_tracker.record(built.usage);
            }
            prompt_cache_events.addAll(built.prompt_cache_events);

            List<PendingToolUse> pending_tool_uses = new ArrayList<>();
            for (ContentBlock block : built.message.blocks()) {
                if (block instanceof ContentBlock.ToolUse use) {
                    pending_tool_uses.add(new PendingToolUse(use.id(), use.name(), use.input()));
                }
            }

            try {
                session.append_message(built.message);
            } catch (RuntimeException error) {
                throw new RuntimeError(error.getMessage(), error);
            }
            assistant_messages.add(built.message);

            if (pending_tool_uses.isEmpty()) {
                break;
            }

            for (PendingToolUse pending : pending_tool_uses) {
                ConversationMessage result_message = dispatch_tool(pending, prompter);
                try {
                    session.append_message(result_message);
                } catch (RuntimeException error) {
                    throw new RuntimeError(error.getMessage(), error);
                }
                tool_results.add(result_message);
            }
        }

        Optional<AutoCompactionEvent> auto_compaction = maybe_auto_compact();

        return new TurnSummary(
                assistant_messages,
                tool_results,
                prompt_cache_events,
                (int) iterations,
                usage_tracker.cumulative_usage(),
                auto_compaction);
    }

    private ConversationMessage dispatch_tool(PendingToolUse pending, PermissionPrompter prompter) {
        PermissionOutcome outcome = permission_policy.authorize_with_context(
                pending.name, pending.input, PermissionContext.defaultContext(), Optional.ofNullable(prompter));

        if (outcome instanceof PermissionOutcome.Deny deny) {
            return ConversationMessage.tool_result(pending.id, pending.name, deny.reason(), true);
        }

        String output;
        boolean is_error;
        try {
            output = tool_executor.execute(pending.name, pending.input);
            is_error = false;
        } catch (ToolError error) {
            output = error.reason();
            is_error = true;
        } catch (RuntimeException error) {
            output = error.getMessage() == null ? error.toString() : error.getMessage();
            is_error = true;
        }
        return ConversationMessage.tool_result(pending.id, pending.name, output, is_error);
    }

    private void run_session_health_probe() {
        if (session.messages().isEmpty() && session.compaction().isPresent()) {
            return;
        }
        try {
            tool_executor.execute("glob_search", "{\"pattern\": \"*.health-check-probe-\"}");
        } catch (ToolError error) {
            throw new RuntimeException("Tool executor probe failed: " + error.reason());
        }
    }

    private Optional<AutoCompactionEvent> maybe_auto_compact() {
        if (usage_tracker.cumulative_usage().input_tokens() < auto_compaction_input_tokens_threshold) {
            return Optional.empty();
        }

        CompactionResult result = compact_session_with_config(
                session, new CompactionConfig(CompactionConfig.DEFAULT_PRESERVE_RECENT_MESSAGES, 0));
        if (result.removed_message_count() == 0) {
            return Optional.empty();
        }

        // The compacted_session is a fresh Session built by the compaction helper —
        // adopt it directly so subsequent turns see the trimmed context.
        this.session = result.compacted_session();
        return Optional.of(new AutoCompactionEvent(result.removed_message_count()));
    }

    static CompactionResult compact_session_with_config(Session session, CompactionConfig config) {
        List<ConversationMessage> messages = session.messages();
        if (messages.size() <= config.preserve_recent_messages()) {
            return new CompactionResult("", "", session, 0);
        }

        // Walk back from the naive cut so we never split a tool-use / tool-result pair.
        int raw_keep_from = messages.size() - config.preserve_recent_messages();
        int keep_from = raw_keep_from;
        while (keep_from > 0) {
            ConversationMessage first_preserved = messages.get(keep_from);
            boolean starts_with_tool_result = !first_preserved.blocks().isEmpty()
                    && first_preserved.blocks().get(0) instanceof ContentBlock.ToolResult;
            if (!starts_with_tool_result) {
                break;
            }
            ConversationMessage preceding = messages.get(keep_from - 1);
            boolean preceding_has_tool_use =
                    preceding.blocks().stream().anyMatch(b -> b instanceof ContentBlock.ToolUse);
            if (preceding_has_tool_use) {
                keep_from -= 1;
                break;
            }
            keep_from -= 1;
        }

        List<ConversationMessage> removed = new ArrayList<>(messages.subList(0, keep_from));
        List<ConversationMessage> preserved = new ArrayList<>(messages.subList(keep_from, messages.size()));

        String summary = build_summary_text(removed);
        ConversationMessage continuation = new ConversationMessage(
                MessageRole.SYSTEM,
                List.of(new ContentBlock.Text(format_continuation(summary, !preserved.isEmpty()))),
                null);

        List<ConversationMessage> compacted = new ArrayList<>();
        compacted.add(continuation);
        compacted.addAll(preserved);

        Session compacted_session = session.forked(null);
        compacted_session.replace_messages_for_compaction(compacted, summary, removed.size());
        return new CompactionResult(summary, summary, compacted_session, removed.size());
    }

    private static String build_summary_text(List<ConversationMessage> removed) {
        // Phase 1 stub: capture enough metadata that downstream tests recognize a
        // compacted session. The richer Rust summarizer is deferred.
        return "Conversation summary: compacted " + removed.size() + " earlier messages.";
    }

    private static String format_continuation(String summary, boolean preserved_recent) {
        StringBuilder builder = new StringBuilder();
        builder.append("This session is being continued from a previous conversation that ran out of context. ")
                .append("The summary below covers the earlier portion of the conversation.\n\n")
                .append(summary);
        if (preserved_recent) {
            builder.append("\n\nRecent messages are preserved verbatim.");
        }
        return builder.toString();
    }

    /** Reads the automatic compaction threshold from the environment. */
    public static long auto_compaction_threshold_from_env() {
        return parse_auto_compaction_threshold(System.getenv(AUTO_COMPACTION_THRESHOLD_ENV_VAR));
    }

    static long parse_auto_compaction_threshold(String raw) {
        if (raw == null) {
            return DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD;
        }
        try {
            long parsed = Long.parseLong(trimmed);
            if (parsed <= 0) {
                return DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD;
        }
    }

    static BuildResult build_assistant_message(List<AssistantEvent> events) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        List<PromptCacheEvent> prompt_cache_events = new ArrayList<>();
        boolean finished = false;
        TokenUsage usage = null;

        for (AssistantEvent event : events) {
            if (event instanceof AssistantEvent.TextDelta delta) {
                text.append(delta.text());
            } else if (event instanceof AssistantEvent.ToolUse tool_use) {
                flush_text_block(text, blocks);
                blocks.add(new ContentBlock.ToolUse(tool_use.id(), tool_use.name(), tool_use.input()));
            } else if (event instanceof AssistantEvent.Usage value) {
                usage = value.usage();
            } else if (event instanceof AssistantEvent.PromptCache cache) {
                prompt_cache_events.add(cache.event());
            } else if (event instanceof AssistantEvent.MessageStop) {
                finished = true;
            }
        }
        flush_text_block(text, blocks);

        if (!finished) {
            throw new RuntimeError("assistant stream ended without a message stop event");
        }
        if (blocks.isEmpty()) {
            throw new RuntimeError("assistant stream produced no content");
        }

        ConversationMessage message = ConversationMessage.assistant_with_usage(blocks, usage);
        return new BuildResult(message, usage, prompt_cache_events);
    }

    private static void flush_text_block(StringBuilder text, List<ContentBlock> blocks) {
        if (text.length() > 0) {
            blocks.add(new ContentBlock.Text(text.toString()));
            text.setLength(0);
        }
    }

    record BuildResult(ConversationMessage message, TokenUsage usage, List<PromptCacheEvent> prompt_cache_events) {}

    private record PendingToolUse(String id, String name, String input) {}
}
