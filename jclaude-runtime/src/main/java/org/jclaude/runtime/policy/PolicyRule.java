package org.jclaude.runtime.policy;

import java.util.Objects;

public final class PolicyRule {
    private final String name;
    private final PolicyCondition condition;
    private final PolicyAction action;
    private final int priority;

    public PolicyRule(String name, PolicyCondition condition, PolicyAction action, int priority) {
        this.name = name;
        this.condition = condition;
        this.action = action;
        this.priority = priority;
    }

    public String name() {
        return name;
    }

    public PolicyCondition condition() {
        return condition;
    }

    public PolicyAction action() {
        return action;
    }

    public int priority() {
        return priority;
    }

    public boolean matches(LaneContext context) {
        return condition.matches(context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolicyRule other)) {
            return false;
        }
        return priority == other.priority
                && Objects.equals(name, other.name)
                && Objects.equals(condition, other.condition)
                && Objects.equals(action, other.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, condition, action, priority);
    }
}
