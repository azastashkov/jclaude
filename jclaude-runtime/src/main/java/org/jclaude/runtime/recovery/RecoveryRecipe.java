package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Encodes the steps to attempt for a given {@link FailureScenario}. */
public record RecoveryRecipe(
        @JsonProperty("scenario") FailureScenario scenario,
        @JsonProperty("steps") List<RecoveryStep> steps,
        @JsonProperty("max_attempts") int max_attempts,
        @JsonProperty("escalation_policy") EscalationPolicy escalation_policy) {

    @JsonCreator
    public RecoveryRecipe {
        steps = List.copyOf(steps);
    }
}
