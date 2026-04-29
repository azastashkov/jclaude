package org.jclaude.runtime.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PolicyEngine {
    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        List<PolicyRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(PolicyRule::priority));
        this.rules = List.copyOf(sorted);
    }

    public List<PolicyRule> rules() {
        return rules;
    }

    public List<PolicyAction> evaluate(LaneContext context) {
        return evaluate(this, context);
    }

    public static List<PolicyAction> evaluate(PolicyEngine engine, LaneContext context) {
        List<PolicyAction> actions = new ArrayList<>();
        for (PolicyRule rule : engine.rules) {
            if (rule.matches(context)) {
                rule.action().flatten_into(actions);
            }
        }
        return actions;
    }
}
