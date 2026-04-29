package org.jclaude.runtime.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Message captured during a task's lifecycle (typically a user-supplied update). */
public record TaskMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("timestamp") long timestamp) {

    @JsonCreator
    public TaskMessage {}
}
