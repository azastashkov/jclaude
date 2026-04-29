package org.jclaude.runtime.branch;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A detected collision between branch lock intents. */
public record BranchLockCollision(
        @JsonProperty("branch") String branch,
        @JsonProperty("module") String module,
        @JsonProperty("laneIds") List<String> lane_ids) {

    public BranchLockCollision {
        lane_ids = List.copyOf(lane_ids);
    }
}
