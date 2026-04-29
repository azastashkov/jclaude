package org.jclaude.runtime.branch;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/** Intent record for a branch lock declared by a lane. */
public record BranchLockIntent(
        @JsonProperty("laneId") String lane_id,
        @JsonProperty("branch") String branch,
        @JsonProperty("worktree") Optional<String> worktree,
        @JsonProperty("modules") List<String> modules) {

    public BranchLockIntent {
        modules = modules == null ? List.of() : List.copyOf(modules);
        worktree = worktree == null ? Optional.empty() : worktree;
    }

    public BranchLockIntent(String lane_id, String branch, String worktree, List<String> modules) {
        this(lane_id, branch, Optional.ofNullable(worktree), modules);
    }
}
