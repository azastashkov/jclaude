package org.jclaude.runtime.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Metadata describing the latest compaction that summarized a session. */
public record SessionCompaction(
        @JsonProperty("count") int count,
        @JsonProperty("removed_message_count") int removed_message_count,
        @JsonProperty("summary") String summary) {

    @JsonCreator
    public SessionCompaction {}
}
