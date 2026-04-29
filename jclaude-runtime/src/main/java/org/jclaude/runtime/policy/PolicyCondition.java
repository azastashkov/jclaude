package org.jclaude.runtime.policy;

import java.time.Duration;
import java.util.List;

/** Predicate evaluated against a {@link LaneContext} to decide whether a {@link PolicyRule} fires. */
public sealed interface PolicyCondition {
    Duration STALE_BRANCH_THRESHOLD = Duration.ofSeconds(60L * 60L);

    record And(List<PolicyCondition> conditions) implements PolicyCondition {
        public And {
            conditions = List.copyOf(conditions);
        }
    }

    record Or(List<PolicyCondition> conditions) implements PolicyCondition {
        public Or {
            conditions = List.copyOf(conditions);
        }
    }

    record GreenAt(int level) implements PolicyCondition {}

    record StaleBranch() implements PolicyCondition {}

    record StartupBlocked() implements PolicyCondition {}

    record LaneCompleted() implements PolicyCondition {}

    record LaneReconciled() implements PolicyCondition {}

    record ReviewPassed() implements PolicyCondition {}

    record ScopedDiff() implements PolicyCondition {}

    record TimedOut(Duration duration) implements PolicyCondition {}

    default boolean matches(LaneContext context) {
        return switch (this) {
            case And a -> a.conditions().stream().allMatch(c -> c.matches(context));
            case Or o -> o.conditions().stream().anyMatch(c -> c.matches(context));
            case GreenAt g -> context.green_level() >= g.level();
            case StaleBranch ignored -> context.branch_freshness().compareTo(STALE_BRANCH_THRESHOLD) >= 0;
            case StartupBlocked ignored -> context.blocker() == LaneBlocker.STARTUP;
            case LaneCompleted ignored -> context.completed();
            case LaneReconciled ignored -> context.reconciled();
            case ReviewPassed ignored -> context.review_status() == ReviewStatus.APPROVED;
            case ScopedDiff ignored -> context.diff_scope() == DiffScope.SCOPED;
            case TimedOut t -> context.branch_freshness().compareTo(t.duration()) >= 0;
        };
    }
}
