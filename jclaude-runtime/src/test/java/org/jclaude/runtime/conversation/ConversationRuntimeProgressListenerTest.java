package org.jclaude.runtime.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jclaude.runtime.permissions.PermissionMode;
import org.jclaude.runtime.permissions.PermissionPolicy;
import org.jclaude.runtime.permissions.PermissionPromptDecision;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.session.Session;
import org.junit.jupiter.api.Test;

class ConversationRuntimeProgressListenerTest {

    @Test
    void runtime_forwards_supplied_progress_listener_to_api_client_each_iteration() {
        // Records every (listener-identity-tagged) signal across both iterations of the loop.
        List<String> received = new ArrayList<>();

        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_text_delta_received(int char_count) {
                received.add("text:" + char_count);
            }

            @Override
            public void on_tool_starting(String tool_name) {
                received.add("tool:" + tool_name);
            }
        };

        // Two-iteration scripted client: first call returns a tool-use, second returns plain text.
        // The 2-arg overload must be invoked by the runtime; we assert by emitting from there.
        ApiClient api_client = new ApiClient() {
            int call = 0;

            @Override
            public List<AssistantEvent> stream(ApiRequest request) {
                throw new AssertionError("runtime must call the 2-arg stream overload");
            }

            @Override
            public List<AssistantEvent> stream(ApiRequest request, ProgressListener supplied) {
                call += 1;
                if (call == 1) {
                    supplied.on_tool_starting("noop");
                    return List.of(
                            new AssistantEvent.ToolUse("call_1", "noop", "{}"), AssistantEvent.MessageStop.INSTANCE);
                }
                supplied.on_text_delta_received(11);
                return List.of(new AssistantEvent.TextDelta("hello world"), AssistantEvent.MessageStop.INSTANCE);
            }
        };

        StaticToolExecutor tools = StaticToolExecutor.create().register("noop", input -> "ok");
        ConversationRuntime runtime = new ConversationRuntime(
                Session.create(), api_client, tools, new PermissionPolicy(PermissionMode.WORKSPACE_WRITE), List.of("s"))
                .with_progress_listener(listener);

        PermissionPrompter allow = request -> new PermissionPromptDecision.Allow();
        runtime.run_turn("hi", allow);

        assertThat(received).containsExactly("tool:noop", "text:11");
    }

    @Test
    void runtime_uses_no_op_listener_when_none_supplied_and_does_not_throw() {
        ApiClient api_client = new ApiClient() {
            @Override
            public List<AssistantEvent> stream(ApiRequest request) {
                throw new AssertionError("runtime must call the 2-arg stream overload");
            }

            @Override
            public List<AssistantEvent> stream(ApiRequest request, ProgressListener supplied) {
                // The default listener must be a real reference so adapters can call it freely.
                assertThat(supplied).isNotNull();
                supplied.on_tool_starting("anything");
                supplied.on_text_delta_received(99);
                return List.of(new AssistantEvent.TextDelta("ok"), AssistantEvent.MessageStop.INSTANCE);
            }
        };
        ConversationRuntime runtime = new ConversationRuntime(
                Session.create(),
                api_client,
                StaticToolExecutor.create(),
                new PermissionPolicy(PermissionMode.WORKSPACE_WRITE),
                List.of("s"));

        runtime.run_turn("hi", request -> new PermissionPromptDecision.Allow());
    }
}
