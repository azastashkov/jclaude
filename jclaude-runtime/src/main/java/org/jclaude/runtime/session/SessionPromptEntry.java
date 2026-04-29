package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A single user prompt recorded with a timestamp for history tracking. */
public record SessionPromptEntry(@JsonProperty("timestamp_ms") long timestamp_ms, @JsonProperty("text") String text) {

    @JsonCreator
    public SessionPromptEntry {}
}
