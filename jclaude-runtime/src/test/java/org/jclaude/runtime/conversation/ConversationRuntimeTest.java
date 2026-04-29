package org.jclaude.runtime.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jclaude.runtime.permissions.PermissionMode;
import org.jclaude.runtime.permissions.PermissionPolicy;
import org.jclaude.runtime.permissions.PermissionPromptDecision;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.permissions.PermissionRequest;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.session.Session;
import org.jclaude.runtime.session.SessionCompaction;
import org.jclaude.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationRuntimeTest {

    @Test
    void runs_user_to_tool_to_result_loop_end_to_end_and_tracks_usage() {
        ScriptedApiClient api_client = new ScriptedApiClient();
        StaticToolExecutor tool_executor = StaticToolExecutor.create().register("add", input -> {
            int total = 0;
            for (String part : input.split(",")) {
                total += Integer.parseInt(part);
            }
            return Integer.toString(total);
        });
        PermissionPolicy permission_policy = new PermissionPolicy(PermissionMode.WORKSPACE_WRITE);
        ConversationRuntime runtime = new ConversationRuntime(
                Session.create(), api_client, tool_executor, permission_policy, List.of("system"));

        TurnSummary summary = runtime.run_turn("what is 2 + 2?", new PromptAllowOnce());

        assertThat(summary.iterations()).isEqualTo(2);
        assertThat(summary.assistant_messages()).hasSize(2);
        assertThat(summary.tool_results()).hasSize(1);
        assertThat(summary.prompt_cache_events()).hasSize(1);
        assertThat(runtime.session().messages()).hasSize(4);
        assertThat(summary.usage().output_tokens()).isEqualTo(10);
        assertThat(summary.auto_compaction()).isEmpty();
        assertThat(runtime.session().messages().get(1).blocks().get(1)).isInstanceOf(ContentBlock.ToolUse.class);
        ContentBlock tool_result_block =
                runtime.session().messages().get(2).blocks().get(0);
        assertThat(tool_result_block).isInstanceOf(ContentBlock.ToolResult.class);
        assertThat(((ContentBlock.ToolResult) tool_result_block).is_error()).isFalse();
    }

    @Test
    void records_denied_tool_results_when_prompt_rejects() {
        PermissionPrompter reject_prompter = request -> new PermissionPromptDecision.Deny("not now");

        ApiClient api_client = request -> {
            boolean has_tool_message =
                    request.messages().stream().anyMatch(message -> message.role() == MessageRole.TOOL);
            if (has_tool_message) {
                return List.of(
                        new AssistantEvent.TextDelta("I could not use the tool."), AssistantEvent.MessageStop.INSTANCE);
            }
            return List.of(
                    new AssistantEvent.ToolUse("tool-1", "blocked", "secret"), AssistantEvent.MessageStop.INSTANCE);
        };

        ConversationRuntime runtime = new ConversationRuntime(
                Session.create(),
                api_client,
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.WORKSPACE_WRITE),
                List.of("system"));

        TurnSummary summary = runtime.run_turn("use the tool", reject_prompter);

        assertThat(summary.tool_results()).hasSize(1);
        ContentBlock block = summary.tool_results().get(0).blocks().get(0);
        assertThat(block).isInstanceOf(ContentBlock.ToolResult.class);
        ContentBlock.ToolResult result = (ContentBlock.ToolResult) block;
        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).isEqualTo("not now");
    }

    @Test
    void reconstructs_usage_tracker_from_restored_session() {
        ApiClient api_client =
                request -> List.of(new AssistantEvent.TextDelta("done"), AssistantEvent.MessageStop.INSTANCE);

        Session session = Session.create();
        session.append_message(ConversationMessage.assistant_with_usage(
                List.of(new ContentBlock.Text("earlier")), new TokenUsage(11, 7, 2, 1)));

        ConversationRuntime runtime = new ConversationRuntime(
                session,
                api_client,
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        assertThat(runtime.usage().turns()).isEqualTo(1);
        assertThat(runtime.usage().cumulative_usage().total_tokens()).isEqualTo(21);
    }

    @Test
    void persists_conversation_turn_messages_to_jsonl_session(@TempDir Path tempDir) throws IOException {
        ApiClient api_client =
                request -> List.of(new AssistantEvent.TextDelta("done"), AssistantEvent.MessageStop.INSTANCE);

        Path path = tempDir.resolve("persisted-turn.jsonl");
        Session session = Session.create().with_persistence_path(path);
        ConversationRuntime runtime = new ConversationRuntime(
                session,
                api_client,
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        runtime.run_turn("persist this turn", null);

        Session restored = Session.load_from_path(path);
        Files.deleteIfExists(path);

        assertThat(restored.messages()).hasSize(2);
        assertThat(restored.messages().get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(restored.messages().get(1).role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(restored.session_id()).isEqualTo(runtime.session().session_id());
    }

    @Test
    void forks_runtime_session_without_mutating_original() {
        Session session = Session.create();
        session.push_user_text("branch me");

        ConversationRuntime runtime = new ConversationRuntime(
                session,
                new ScriptedApiClient(),
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        Session forked = runtime.fork_session("alt-path");

        assertThat(forked.messages()).isEqualTo(session.messages());
        assertThat(forked.session_id()).isNotEqualTo(session.session_id());
        assertThat(forked.fork()).isPresent();
        assertThat(forked.fork().get().parent_session_id()).isEqualTo(session.session_id());
        assertThat(forked.fork().get().branch_name()).isEqualTo("alt-path");
        assertThat(runtime.session().fork()).isEmpty();
    }

    @Test
    void auto_compacts_when_cumulative_input_threshold_is_crossed() {
        ApiClient api_client = request -> List.of(
                new AssistantEvent.TextDelta("done"),
                new AssistantEvent.Usage(new TokenUsage(120_000, 4, 0, 0)),
                AssistantEvent.MessageStop.INSTANCE);

        Session session = Session.create();
        session.append_message(ConversationMessage.user_text("one"));
        session.append_message(ConversationMessage.assistant(List.of(new ContentBlock.Text("two"))));
        session.append_message(ConversationMessage.user_text("three"));
        session.append_message(ConversationMessage.assistant(List.of(new ContentBlock.Text("four"))));

        ConversationRuntime runtime = new ConversationRuntime(
                        session,
                        api_client,
                        StaticToolExecutor.create(),
                        new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                        List.of("system"))
                .with_auto_compaction_input_tokens_threshold(100_000);

        TurnSummary summary = runtime.run_turn("trigger", null);

        assertThat(summary.auto_compaction()).isPresent();
        assertThat(summary.auto_compaction().get().removed_message_count()).isEqualTo(2);
        assertThat(runtime.session().messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
    }

    @Test
    void skips_auto_compaction_below_threshold() {
        ApiClient api_client = request -> List.of(
                new AssistantEvent.TextDelta("done"),
                new AssistantEvent.Usage(new TokenUsage(99_999, 4, 0, 0)),
                AssistantEvent.MessageStop.INSTANCE);

        ConversationRuntime runtime = new ConversationRuntime(
                        Session.create(),
                        api_client,
                        StaticToolExecutor.create(),
                        new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                        List.of("system"))
                .with_auto_compaction_input_tokens_threshold(100_000);

        TurnSummary summary = runtime.run_turn("trigger", null);
        assertThat(summary.auto_compaction()).isEmpty();
        assertThat(runtime.session().messages()).hasSize(2);
    }

    @Test
    void auto_compaction_threshold_defaults_and_parses_values() {
        assertThat(ConversationRuntime.parse_auto_compaction_threshold(null))
                .isEqualTo(ConversationRuntime.DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD);
        assertThat(ConversationRuntime.parse_auto_compaction_threshold("4321")).isEqualTo(4321L);
        assertThat(ConversationRuntime.parse_auto_compaction_threshold("0"))
                .isEqualTo(ConversationRuntime.DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD);
        assertThat(ConversationRuntime.parse_auto_compaction_threshold("not-a-number"))
                .isEqualTo(ConversationRuntime.DEFAULT_AUTO_COMPACTION_INPUT_TOKENS_THRESHOLD);
    }

    @Test
    void compaction_health_probe_blocks_turn_when_tool_executor_is_broken() {
        ApiClient api_client = request -> {
            throw new AssertionError("API should not run when health probe fails");
        };

        Session session = Session.create();
        session.record_compaction(new SessionCompaction(0, 4, "summarized earlier work"));
        session.push_user_text("previous message");

        StaticToolExecutor tool_executor = StaticToolExecutor.create().register("glob_search", input -> {
            throw new ToolError("transport unavailable");
        });
        ConversationRuntime runtime = new ConversationRuntime(
                session,
                api_client,
                tool_executor,
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        assertThatThrownBy(() -> runtime.run_turn("trigger", null))
                .isInstanceOf(RuntimeError.class)
                .hasMessageContaining("Session health probe failed after compaction")
                .hasMessageContaining("transport unavailable");
    }

    @Test
    void compaction_health_probe_skips_empty_compacted_session() {
        ApiClient api_client =
                request -> List.of(new AssistantEvent.TextDelta("done"), AssistantEvent.MessageStop.INSTANCE);

        Session session = Session.create();
        session.record_compaction(new SessionCompaction(0, 2, "fresh summary"));

        StaticToolExecutor tool_executor = StaticToolExecutor.create().register("glob_search", input -> {
            throw new ToolError("glob_search should not run for an empty compacted session");
        });
        ConversationRuntime runtime = new ConversationRuntime(
                session,
                api_client,
                tool_executor,
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        TurnSummary summary = runtime.run_turn("trigger", null);
        assertThat(summary.auto_compaction()).isEmpty();
        assertThat(runtime.session().messages()).hasSize(2);
    }

    @Test
    void build_assistant_message_requires_message_stop_event() {
        List<AssistantEvent> events = List.of(new AssistantEvent.TextDelta("hello"));
        assertThatThrownBy(() -> ConversationRuntime.build_assistant_message(events))
                .isInstanceOf(RuntimeError.class)
                .hasMessageContaining("assistant stream ended without a message stop event");
    }

    @Test
    void build_assistant_message_requires_content() {
        List<AssistantEvent> events = List.of(AssistantEvent.MessageStop.INSTANCE);
        assertThatThrownBy(() -> ConversationRuntime.build_assistant_message(events))
                .isInstanceOf(RuntimeError.class)
                .hasMessageContaining("assistant stream produced no content");
    }

    @Test
    void static_tool_executor_rejects_unknown_tools() {
        StaticToolExecutor executor = StaticToolExecutor.create();
        assertThatThrownBy(() -> executor.execute("missing", "{}"))
                .isInstanceOf(ToolError.class)
                .hasMessage("unknown tool: missing");
    }

    @Test
    void run_turn_errors_when_max_iterations_is_exceeded() {
        ApiClient api_client = request ->
                List.of(new AssistantEvent.ToolUse("tool-1", "echo", "payload"), AssistantEvent.MessageStop.INSTANCE);

        ConversationRuntime runtime = new ConversationRuntime(
                        Session.create(),
                        api_client,
                        StaticToolExecutor.create().register("echo", input -> input),
                        new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                        List.of("system"))
                .with_max_iterations(1);

        assertThatThrownBy(() -> runtime.run_turn("loop", null))
                .isInstanceOf(RuntimeError.class)
                .hasMessageContaining("conversation loop exceeded the maximum number of iterations");
    }

    @Test
    void run_turn_propagates_api_errors() {
        ApiClient api_client = request -> {
            throw new RuntimeError("upstream failed");
        };

        ConversationRuntime runtime = new ConversationRuntime(
                Session.create(),
                api_client,
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS),
                List.of("system"));

        assertThatThrownBy(() -> runtime.run_turn("hello", null))
                .isInstanceOf(RuntimeError.class)
                .hasMessage("upstream failed");
    }

    /** Two-call scripted client mirroring the Rust {@code ScriptedApiClient}. */
    private static final class ScriptedApiClient implements ApiClient {

        private int call_count = 0;

        @Override
        public List<AssistantEvent> stream(ApiRequest request) {
            call_count += 1;
            switch (call_count) {
                case 1 -> {
                    boolean has_user =
                            request.messages().stream().anyMatch(message -> message.role() == MessageRole.USER);
                    assertThat(has_user).isTrue();
                    return List.of(
                            new AssistantEvent.TextDelta("Let me calculate that."),
                            new AssistantEvent.ToolUse("tool-1", "add", "2,2"),
                            new AssistantEvent.Usage(new TokenUsage(20, 6, 1, 2)),
                            AssistantEvent.MessageStop.INSTANCE);
                }
                case 2 -> {
                    ConversationMessage last =
                            request.messages().get(request.messages().size() - 1);
                    assertThat(last.role()).isEqualTo(MessageRole.TOOL);
                    return List.of(
                            new AssistantEvent.TextDelta("The answer is 4."),
                            new AssistantEvent.Usage(new TokenUsage(24, 4, 1, 3)),
                            new AssistantEvent.PromptCache(new PromptCacheEvent(
                                    true,
                                    "cache read tokens dropped while prompt fingerprint remained stable",
                                    6_000,
                                    1_000,
                                    5_000)),
                            AssistantEvent.MessageStop.INSTANCE);
                }
                default -> throw new AssertionError("extra API call");
            }
        }
    }

    private static final class PromptAllowOnce implements PermissionPrompter {

        @Override
        public PermissionPromptDecision decide(PermissionRequest request) {
            assertThat(request.tool_name()).isEqualTo("add");
            return new PermissionPromptDecision.Allow();
        }
    }

    @SuppressWarnings("unused")
    private static List<ConversationMessage> immutable_messages(List<ConversationMessage> input) {
        return new ArrayList<>(input);
    }
}
