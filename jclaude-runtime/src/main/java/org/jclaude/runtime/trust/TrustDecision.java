package org.jclaude.runtime.trust;

import java.util.List;
import java.util.Optional;

/** Decision returned by the trust resolver. */
public sealed interface TrustDecision {

    Optional<TrustPolicy> policy();

    List<TrustEvent> events();

    record NotRequired() implements TrustDecision {
        @Override
        public Optional<TrustPolicy> policy() {
            return Optional.empty();
        }

        @Override
        public List<TrustEvent> events() {
            return List.of();
        }
    }

    record Required(TrustPolicy policy_value, List<TrustEvent> events_list) implements TrustDecision {
        public Required {
            events_list = List.copyOf(events_list);
        }

        @Override
        public Optional<TrustPolicy> policy() {
            return Optional.of(policy_value);
        }

        @Override
        public List<TrustEvent> events() {
            return events_list;
        }
    }
}
