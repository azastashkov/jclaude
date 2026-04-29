package org.jclaude.runtime.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.session.Session;
import org.junit.jupiter.api.Test;

class CompactionTest {

    private static Session session_with(List<ConversationMessage> messages) {
        Session session = Session.create();
        for (ConversationMessage message : messages) {
            session.append_message(message);
        }
        return session;
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    @Test
    void formats_compact_summary_like_upstream() {
        String summary = "<analysis>scratch</analysis>\n<summary>Kept work</summary>";
        assertThat(Compaction.format_compact_summary(summary)).isEqualTo("Summary:\nKept work");
    }

    @Test
    void leaves_small_sessions_unchanged() {
        Session session = session_with(List.of(ConversationMessage.user_text("hello")));

        CompactionResult result = Compaction.compact_session(session, CompactionConfig.defaults());
        assertThat(result.removed_message_count()).isZero();
        assertThat(result.compacted_session()).isEqualTo(session);
        assertThat(result.summary()).isEmpty();
        assertThat(result.formatted_summary()).isEmpty();
    }

    @Test
    void compacts_older_messages_into_a_system_summary() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user_text(repeat("one ", 200)));
        messages.add(ConversationMessage.assistant(List.of(new ContentBlock.Text(repeat("two ", 200)))));
        messages.add(ConversationMessage.tool_result("1", "bash", repeat("ok ", 200), false));
        messages.add(new ConversationMessage(MessageRole.ASSISTANT, List.of(new ContentBlock.Text("recent")), null));
        Session session = session_with(messages);

        CompactionConfig tight = new CompactionConfig(2, 1);
        CompactionResult result = Compaction.compact_session(session, tight);

        assertThat(result.removed_message_count())
                .as("expected at most 2 removed, got %d", result.removed_message_count())
                .isLessThanOrEqualTo(2);
        assertThat(result.compacted_session().messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(result.compacted_session().messages().get(0).blocks().get(0))
                .isInstanceOfSatisfying(
                        ContentBlock.Text.class, text -> assertThat(text.text()).contains("Summary:"));
        assertThat(result.formatted_summary()).contains("Scope:");
        assertThat(result.formatted_summary()).contains("Key timeline:");
        assertThat(Compaction.should_compact(session, tight)).isTrue();
        assertThat(result.removed_message_count())
                .as("compaction must remove at least one message")
                .isGreaterThan(0);
    }

    @Test
    void keeps_previous_compacted_context_when_compacting_again() {
        List<ConversationMessage> initial_messages = List.of(
                ConversationMessage.user_text("Investigate rust/crates/runtime/src/compact.rs"),
                ConversationMessage.assistant(List.of(new ContentBlock.Text("I will inspect the compact flow."))),
                ConversationMessage.user_text("Also update rust/crates/runtime/src/conversation.rs"),
                ConversationMessage.assistant(
                        List.of(new ContentBlock.Text("Next: preserve prior summary context during auto compact."))));
        Session initial_session = session_with(initial_messages);
        CompactionConfig config = new CompactionConfig(2, 1);

        CompactionResult first = Compaction.compact_session(initial_session, config);
        List<ConversationMessage> follow_up =
                new ArrayList<>(first.compacted_session().messages());
        follow_up.add(ConversationMessage.user_text("Please add regression tests for compaction."));
        follow_up.add(
                ConversationMessage.assistant(List.of(new ContentBlock.Text("Working on regression coverage now."))));

        Session second_session = session_with(follow_up);
        CompactionResult second = Compaction.compact_session(second_session, config);

        assertThat(second.formatted_summary()).contains("Previously compacted context:");
        assertThat(second.formatted_summary()).contains("Scope: 2 earlier messages compacted");
        assertThat(second.formatted_summary()).contains("Newly compacted context:");
        assertThat(second.formatted_summary()).contains("Also update rust/crates/runtime/src/conversation.rs");
        ContentBlock first_block =
                second.compacted_session().messages().get(0).blocks().get(0);
        assertThat(first_block).isInstanceOfSatisfying(ContentBlock.Text.class, text -> {
            assertThat(text.text()).contains("Previously compacted context:");
            assertThat(text.text()).contains("Newly compacted context:");
        });
        ContentBlock second_block =
                second.compacted_session().messages().get(1).blocks().get(0);
        assertThat(second_block).isInstanceOfSatisfying(ContentBlock.Text.class, text -> assertThat(text.text())
                .contains("Please add regression tests for compaction."));
    }

    @Test
    void ignores_existing_compacted_summary_when_deciding_to_recompact() {
        String summary =
                "<summary>Conversation summary:\n- Scope: earlier work preserved.\n- Key timeline:\n  - user: large preserved context\n</summary>";
        Session session = session_with(List.of(
                new ConversationMessage(
                        MessageRole.SYSTEM,
                        List.of(new ContentBlock.Text(
                                Compaction.get_compact_continuation_message(summary, true, true))),
                        null),
                ConversationMessage.user_text("tiny"),
                ConversationMessage.assistant(List.of(new ContentBlock.Text("recent")))));

        assertThat(Compaction.should_compact(session, new CompactionConfig(2, 1)))
                .isFalse();
    }

    @Test
    void truncates_long_blocks_in_summary() {
        String summary = Compaction.summarize_block(new ContentBlock.Text(repeat("x", 400)));
        assertThat(summary).endsWith("…");
        assertThat(summary.codePointCount(0, summary.length())).isLessThanOrEqualTo(161);
    }

    @Test
    void extracts_key_files_from_message_content() {
        List<String> files = Compaction.collect_key_files(List.of(ConversationMessage.user_text(
                "Update rust/crates/runtime/src/compact.rs and rust/crates/rusty-claude-cli/src/main.rs next.")));
        assertThat(files).contains("rust/crates/runtime/src/compact.rs");
        assertThat(files).contains("rust/crates/rusty-claude-cli/src/main.rs");
    }

    @Test
    void compaction_does_not_split_tool_use_tool_result_pair() {
        String tool_id = "call_abc";
        Session session = Session.create();
        session.append_message(ConversationMessage.user_text("Search for files"));
        session.append_message(ConversationMessage.assistant(
                List.of(new ContentBlock.ToolUse(tool_id, "search", "{\"q\":\"*.rs\"}"))));
        session.append_message(ConversationMessage.tool_result(tool_id, "search", "found 5 files", false));
        session.append_message(ConversationMessage.assistant(List.of(new ContentBlock.Text("Done."))));

        CompactionConfig config = new CompactionConfig(1, CompactionConfig.DEFAULT_MAX_ESTIMATED_TOKENS);
        CompactionResult result = Compaction.compact_session(session, config);

        List<ConversationMessage> messages = result.compacted_session().messages();
        for (int i = 1; i < messages.size(); i++) {
            boolean curr_is_tool_result = !messages.get(i).blocks().isEmpty()
                    && messages.get(i).blocks().get(0) instanceof ContentBlock.ToolResult;
            if (!curr_is_tool_result) {
                continue;
            }
            boolean prev_has_tool_use =
                    messages.get(i - 1).blocks().stream().anyMatch(b -> b instanceof ContentBlock.ToolUse);
            assertThat(prev_has_tool_use)
                    .as("message[%d] is a ToolResult but message[%d] has no ToolUse", i, i - 1)
                    .isTrue();
        }
    }

    @Test
    void infers_pending_work_from_recent_messages() {
        List<String> pending = Compaction.infer_pending_work(List.of(
                ConversationMessage.user_text("done"),
                ConversationMessage.assistant(
                        List.of(new ContentBlock.Text("Next: update tests and follow up on remaining CLI polish.")))));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0)).contains("Next: update tests");
    }
}
