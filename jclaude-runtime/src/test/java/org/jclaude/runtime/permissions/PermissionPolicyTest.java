package org.jclaude.runtime.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    static final class RecordingPrompter implements PermissionPrompter {
        final List<PermissionRequest> seen = new ArrayList<>();
        boolean allow;

        RecordingPrompter(boolean allow) {
            this.allow = allow;
        }

        @Override
        public PermissionPromptDecision decide(PermissionRequest request) {
            seen.add(request);
            if (allow) {
                return new PermissionPromptDecision.Allow();
            }
            return new PermissionPromptDecision.Deny("not now");
        }
    }

    @Test
    void allows_tools_when_active_mode_meets_requirement() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("read_file", PermissionMode.READ_ONLY)
                .with_tool_requirement("write_file", PermissionMode.WORKSPACE_WRITE);

        assertThat(policy.authorize("read_file", "{}", Optional.empty())).isInstanceOf(PermissionOutcome.Allow.class);
        assertThat(policy.authorize("write_file", "{}", Optional.empty())).isInstanceOf(PermissionOutcome.Allow.class);
    }

    @Test
    void denies_read_only_escalations_without_prompt() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY)
                .with_tool_requirement("write_file", PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS);

        PermissionOutcome writeOutcome = policy.authorize("write_file", "{}", Optional.empty());
        assertThat(writeOutcome).isInstanceOf(PermissionOutcome.Deny.class);
        assertThat(((PermissionOutcome.Deny) writeOutcome).reason()).contains("requires workspace-write permission");

        PermissionOutcome bashOutcome = policy.authorize("bash", "{}", Optional.empty());
        assertThat(bashOutcome).isInstanceOf(PermissionOutcome.Deny.class);
        assertThat(((PermissionOutcome.Deny) bashOutcome).reason()).contains("requires danger-full-access permission");
    }

    @Test
    void prompts_for_workspace_write_to_danger_full_access_escalation() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS);
        RecordingPrompter prompter = new RecordingPrompter(true);

        PermissionOutcome outcome = policy.authorize("bash", "echo hi", Optional.of(prompter));

        assertThat(outcome).isInstanceOf(PermissionOutcome.Allow.class);
        assertThat(prompter.seen).hasSize(1);
        assertThat(prompter.seen.get(0).tool_name()).isEqualTo("bash");
        assertThat(prompter.seen.get(0).current_mode()).isEqualTo(PermissionMode.WORKSPACE_WRITE);
        assertThat(prompter.seen.get(0).required_mode()).isEqualTo(PermissionMode.DANGER_FULL_ACCESS);
    }

    @Test
    void honors_prompt_rejection_reason() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS);
        RecordingPrompter prompter = new RecordingPrompter(false);

        PermissionOutcome outcome = policy.authorize("bash", "echo hi", Optional.of(prompter));

        assertThat(outcome).isInstanceOf(PermissionOutcome.Deny.class);
        assertThat(((PermissionOutcome.Deny) outcome).reason()).isEqualTo("not now");
    }

    @Test
    void applies_rule_based_denials_and_allows() {
        RuntimePermissionRuleConfig rules =
                new RuntimePermissionRuleConfig(List.of("bash(git:*)"), List.of("bash(rm -rf:*)"), List.of());
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS)
                .with_permission_rules(rules);

        assertThat(policy.authorize("bash", "{\"command\":\"git status\"}", Optional.empty()))
                .isInstanceOf(PermissionOutcome.Allow.class);

        PermissionOutcome rmOutcome = policy.authorize("bash", "{\"command\":\"rm -rf /tmp/x\"}", Optional.empty());
        assertThat(rmOutcome).isInstanceOf(PermissionOutcome.Deny.class);
        assertThat(((PermissionOutcome.Deny) rmOutcome).reason()).contains("denied by rule");
    }

    @Test
    void ask_rules_force_prompt_even_when_mode_allows() {
        RuntimePermissionRuleConfig rules =
                new RuntimePermissionRuleConfig(List.of(), List.of(), List.of("bash(git:*)"));
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS)
                .with_permission_rules(rules);
        RecordingPrompter prompter = new RecordingPrompter(true);

        PermissionOutcome outcome = policy.authorize("bash", "{\"command\":\"git status\"}", Optional.of(prompter));

        assertThat(outcome).isInstanceOf(PermissionOutcome.Allow.class);
        assertThat(prompter.seen).hasSize(1);
        assertThat(prompter.seen.get(0).reason()).isPresent();
        assertThat(prompter.seen.get(0).reason().get()).contains("ask rule");
    }

    @Test
    void hook_allow_still_respects_ask_rules() {
        RuntimePermissionRuleConfig rules =
                new RuntimePermissionRuleConfig(List.of(), List.of(), List.of("bash(git:*)"));
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS)
                .with_permission_rules(rules);
        PermissionContext context =
                new PermissionContext(Optional.of(PermissionOverride.ALLOW), Optional.of("hook approved"));
        RecordingPrompter prompter = new RecordingPrompter(true);

        PermissionOutcome outcome =
                policy.authorize_with_context("bash", "{\"command\":\"git status\"}", context, Optional.of(prompter));

        assertThat(outcome).isInstanceOf(PermissionOutcome.Allow.class);
        assertThat(prompter.seen).hasSize(1);
    }

    @Test
    void hook_deny_short_circuits_permission_flow() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS);
        PermissionContext context =
                new PermissionContext(Optional.of(PermissionOverride.DENY), Optional.of("blocked by hook"));

        PermissionOutcome outcome = policy.authorize_with_context("bash", "{}", context, Optional.empty());

        assertThat(outcome).isInstanceOf(PermissionOutcome.Deny.class);
        assertThat(((PermissionOutcome.Deny) outcome).reason()).isEqualTo("blocked by hook");
    }

    @Test
    void hook_ask_forces_prompt() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.DANGER_FULL_ACCESS)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS);
        PermissionContext context =
                new PermissionContext(Optional.of(PermissionOverride.ASK), Optional.of("hook requested confirmation"));
        RecordingPrompter prompter = new RecordingPrompter(true);

        PermissionOutcome outcome = policy.authorize_with_context("bash", "{}", context, Optional.of(prompter));

        assertThat(outcome).isInstanceOf(PermissionOutcome.Allow.class);
        assertThat(prompter.seen).hasSize(1);
        assertThat(prompter.seen.get(0).reason()).isPresent();
        assertThat(prompter.seen.get(0).reason().get()).isEqualTo("hook requested confirmation");
    }
}
