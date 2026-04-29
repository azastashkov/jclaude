package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Provenance recorded when a session is forked from another session. */
public record SessionFork(
        @JsonProperty("parent_session_id") String parent_session_id, @JsonProperty("branch_name") String branch_name) {

    @JsonCreator
    public SessionFork {}
}
