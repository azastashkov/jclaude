package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;

/** Outcome of a recovery attempt. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecoveryResult.Recovered.class, name = "recovered"),
    @JsonSubTypes.Type(value = RecoveryResult.PartialRecovery.class, name = "partial_recovery"),
    @JsonSubTypes.Type(value = RecoveryResult.EscalationRequired.class, name = "escalation_required")
})
public sealed interface RecoveryResult {

    @JsonTypeName("recovered")
    record Recovered(@JsonProperty("steps_taken") int steps_taken) implements RecoveryResult {}

    @JsonTypeName("partial_recovery")
    record PartialRecovery(
            @JsonProperty("recovered") List<RecoveryStep> recovered,
            @JsonProperty("remaining") List<RecoveryStep> remaining)
            implements RecoveryResult {

        public PartialRecovery {
            recovered = List.copyOf(recovered);
            remaining = List.copyOf(remaining);
        }
    }

    @JsonTypeName("escalation_required")
    record EscalationRequired(@JsonProperty("reason") String reason) implements RecoveryResult {}
}
