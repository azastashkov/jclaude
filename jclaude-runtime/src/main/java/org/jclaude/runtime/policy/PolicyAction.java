package org.jclaude.runtime.policy;

import java.util.List;

/** Action emitted by a {@link PolicyRule} when its condition matches. */
public sealed interface PolicyAction {
    record MergeToDev() implements PolicyAction {}

    record MergeForward() implements PolicyAction {}

    record RecoverOnce() implements PolicyAction {}

    record Escalate(String reason) implements PolicyAction {}

    record CloseoutLane() implements PolicyAction {}

    record CleanupSession() implements PolicyAction {}

    record Reconcile(ReconcileReason reason) implements PolicyAction {}

    record Notify(String channel) implements PolicyAction {}

    record Block(String reason) implements PolicyAction {}

    record Chain(List<PolicyAction> actions) implements PolicyAction {
        public Chain {
            actions = List.copyOf(actions);
        }
    }

    default void flatten_into(List<PolicyAction> sink) {
        if (this instanceof Chain chain) {
            for (PolicyAction action : chain.actions()) {
                action.flatten_into(sink);
            }
        } else {
            sink.add(this);
        }
    }
}
