package org.jclaude.runtime.lane;

/** Canonical lane event names. */
public enum LaneEventName {
    STARTED("lane.started"),
    READY("lane.ready"),
    PROMPT_MISDELIVERY("lane.prompt_misdelivery"),
    BLOCKED("lane.blocked"),
    RED("lane.red"),
    GREEN("lane.green"),
    COMMIT_CREATED("lane.commit.created"),
    PR_OPENED("lane.pr.opened"),
    MERGE_READY("lane.merge.ready"),
    FINISHED("lane.finished"),
    FAILED("lane.failed"),
    RECONCILED("lane.reconciled"),
    MERGED("lane.merged"),
    SUPERSEDED("lane.superseded"),
    CLOSED("lane.closed"),
    BRANCH_STALE_AGAINST_MAIN("branch.stale_against_main"),
    BRANCH_WORKSPACE_MISMATCH("branch.workspace_mismatch"),
    SHIP_PREPARED("ship.prepared"),
    SHIP_COMMITS_SELECTED("ship.commits_selected"),
    SHIP_MERGED("ship.merged"),
    SHIP_PUSHED_MAIN("ship.pushed_main");

    private final String wire;

    LaneEventName(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
