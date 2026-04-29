package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** Structured event emitted during recovery. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecoveryEvent.RecoveryAttempted.class, name = "recovery_attempted"),
    @JsonSubTypes.Type(value = RecoveryEvent.RecoverySucceeded.class, name = "recovery_succeeded"),
    @JsonSubTypes.Type(value = RecoveryEvent.RecoveryFailed.class, name = "recovery_failed"),
    @JsonSubTypes.Type(value = RecoveryEvent.Escalated.class, name = "escalated")
})
public sealed interface RecoveryEvent {

    @JsonTypeName("recovery_attempted")
    record RecoveryAttempted(
            @JsonProperty("scenario") FailureScenario scenario,
            @JsonProperty("recipe") RecoveryRecipe recipe,
            @JsonProperty("result") RecoveryResult result)
            implements RecoveryEvent {}

    @JsonTypeName("recovery_succeeded")
    record RecoverySucceeded() implements RecoveryEvent {}

    @JsonTypeName("recovery_failed")
    record RecoveryFailed() implements RecoveryEvent {}

    @JsonTypeName("escalated")
    record Escalated() implements RecoveryEvent {}
}
