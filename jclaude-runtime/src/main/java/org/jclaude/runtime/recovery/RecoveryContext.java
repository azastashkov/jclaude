package org.jclaude.runtime.recovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tracks recovery state and emits structured events. */
public final class RecoveryContext {

    private final Map<FailureScenario, Integer> attempts = new HashMap<>();
    private final List<RecoveryEvent> events = new ArrayList<>();
    private Optional<Integer> fail_at_step = Optional.empty();

    public RecoveryContext() {}

    /** Configures a step index at which simulated execution will fail. */
    public RecoveryContext with_fail_at_step(int index) {
        this.fail_at_step = Optional.of(index);
        return this;
    }

    public List<RecoveryEvent> events() {
        return List.copyOf(events);
    }

    public int attempt_count(FailureScenario scenario) {
        return attempts.getOrDefault(scenario, 0);
    }

    Map<FailureScenario, Integer> attempts_internal() {
        return attempts;
    }

    Optional<Integer> fail_at_step_internal() {
        return fail_at_step;
    }

    void push_event_internal(RecoveryEvent event) {
        events.add(event);
    }
}
