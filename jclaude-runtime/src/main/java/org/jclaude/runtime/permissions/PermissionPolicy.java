package org.jclaude.runtime.permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Evaluates permission mode requirements plus allow/deny/ask rules. */
public final class PermissionPolicy {
    private final PermissionMode active_mode;
    private final TreeMap<String, PermissionMode> tool_requirements;
    private List<PermissionRule> allow_rules;
    private List<PermissionRule> deny_rules;
    private List<PermissionRule> ask_rules;

    public PermissionPolicy(PermissionMode active_mode) {
        this.active_mode = active_mode;
        this.tool_requirements = new TreeMap<>();
        this.allow_rules = new ArrayList<>();
        this.deny_rules = new ArrayList<>();
        this.ask_rules = new ArrayList<>();
    }

    public static PermissionPolicy newPolicy(PermissionMode active_mode) {
        return new PermissionPolicy(active_mode);
    }

    public PermissionPolicy with_tool_requirement(String tool_name, PermissionMode required_mode) {
        this.tool_requirements.put(tool_name, required_mode);
        return this;
    }

    public PermissionPolicy with_permission_rules(RuntimePermissionRuleConfig config) {
        this.allow_rules = config.allow().stream().map(PermissionRule::parse).toList();
        this.deny_rules = config.deny().stream().map(PermissionRule::parse).toList();
        this.ask_rules = config.ask().stream().map(PermissionRule::parse).toList();
        return this;
    }

    public PermissionMode active_mode() {
        return active_mode;
    }

    public PermissionMode required_mode_for(String tool_name) {
        PermissionMode mode = tool_requirements.get(tool_name);
        return mode == null ? PermissionMode.DANGER_FULL_ACCESS : mode;
    }

    public PermissionOutcome authorize(String tool_name, String input, Optional<PermissionPrompter> prompter) {
        return authorize_with_context(tool_name, input, PermissionContext.defaultContext(), prompter);
    }

    public PermissionOutcome authorize_with_context(
            String tool_name, String input, PermissionContext context, Optional<PermissionPrompter> prompter) {
        Optional<PermissionRule> denyRule = find_matching_rule(deny_rules, tool_name, input);
        if (denyRule.isPresent()) {
            return new PermissionOutcome.Deny(
                    String.format("Permission to use %s has been denied by rule '%s'", tool_name, denyRule.get().raw));
        }

        PermissionMode current_mode = active_mode();
        PermissionMode required_mode = required_mode_for(tool_name);
        Optional<PermissionRule> ask_rule = find_matching_rule(ask_rules, tool_name, input);
        Optional<PermissionRule> allow_rule = find_matching_rule(allow_rules, tool_name, input);

        Optional<PermissionOverride> override = context.override_decision();
        if (override.isPresent()) {
            switch (override.get()) {
                case DENY -> {
                    String reason = context.override_reason()
                            .orElseGet(() -> String.format("tool '%s' denied by hook", tool_name));
                    return new PermissionOutcome.Deny(reason);
                }
                case ASK -> {
                    String reason = context.override_reason()
                            .orElseGet(
                                    () -> String.format("tool '%s' requires approval due to hook guidance", tool_name));
                    return prompt_or_deny(tool_name, input, current_mode, required_mode, Optional.of(reason), prompter);
                }
                case ALLOW -> {
                    if (ask_rule.isPresent()) {
                        String reason = String.format(
                                "tool '%s' requires approval due to ask rule '%s'", tool_name, ask_rule.get().raw);
                        return prompt_or_deny(
                                tool_name, input, current_mode, required_mode, Optional.of(reason), prompter);
                    }
                    if (allow_rule.isPresent()
                            || current_mode == PermissionMode.ALLOW
                            || current_mode.compareTo(required_mode) >= 0) {
                        return new PermissionOutcome.Allow();
                    }
                }
            }
        }

        if (ask_rule.isPresent()) {
            String reason =
                    String.format("tool '%s' requires approval due to ask rule '%s'", tool_name, ask_rule.get().raw);
            return prompt_or_deny(tool_name, input, current_mode, required_mode, Optional.of(reason), prompter);
        }

        if (allow_rule.isPresent()
                || current_mode == PermissionMode.ALLOW
                || current_mode.compareTo(required_mode) >= 0) {
            return new PermissionOutcome.Allow();
        }

        if (current_mode == PermissionMode.PROMPT
                || (current_mode == PermissionMode.WORKSPACE_WRITE
                        && required_mode == PermissionMode.DANGER_FULL_ACCESS)) {
            String reason = String.format(
                    "tool '%s' requires approval to escalate from %s to %s",
                    tool_name, current_mode.as_str(), required_mode.as_str());
            return prompt_or_deny(tool_name, input, current_mode, required_mode, Optional.of(reason), prompter);
        }

        return new PermissionOutcome.Deny(String.format(
                "tool '%s' requires %s permission; current mode is %s",
                tool_name, required_mode.as_str(), current_mode.as_str()));
    }

    private static PermissionOutcome prompt_or_deny(
            String tool_name,
            String input,
            PermissionMode current_mode,
            PermissionMode required_mode,
            Optional<String> reason,
            Optional<PermissionPrompter> prompter) {
        PermissionRequest request = new PermissionRequest(tool_name, input, current_mode, required_mode, reason);

        if (prompter.isPresent()) {
            PermissionPromptDecision decision = prompter.get().decide(request);
            if (decision instanceof PermissionPromptDecision.Allow) {
                return new PermissionOutcome.Allow();
            }
            if (decision instanceof PermissionPromptDecision.Deny denyDecision) {
                return new PermissionOutcome.Deny(denyDecision.reason());
            }
            throw new IllegalStateException("Unhandled prompt decision: " + decision);
        }

        String denyReason = reason.orElseGet(() ->
                String.format("tool '%s' requires approval to run while mode is %s", tool_name, current_mode.as_str()));
        return new PermissionOutcome.Deny(denyReason);
    }

    private static Optional<PermissionRule> find_matching_rule(
            List<PermissionRule> rules, String tool_name, String input) {
        return rules.stream().filter(rule -> rule.matches(tool_name, input)).findFirst();
    }
}
