package org.jclaude.runtime.policy;

import java.time.Duration;
import java.util.Objects;

public final class LaneContext {
    private final String lane_id;
    private final int green_level;
    private final Duration branch_freshness;
    private final LaneBlocker blocker;
    private final ReviewStatus review_status;
    private final DiffScope diff_scope;
    private final boolean completed;
    private final boolean reconciled;

    public LaneContext(
            String lane_id,
            int green_level,
            Duration branch_freshness,
            LaneBlocker blocker,
            ReviewStatus review_status,
            DiffScope diff_scope,
            boolean completed) {
        this(lane_id, green_level, branch_freshness, blocker, review_status, diff_scope, completed, false);
    }

    private LaneContext(
            String lane_id,
            int green_level,
            Duration branch_freshness,
            LaneBlocker blocker,
            ReviewStatus review_status,
            DiffScope diff_scope,
            boolean completed,
            boolean reconciled) {
        this.lane_id = lane_id;
        this.green_level = green_level;
        this.branch_freshness = branch_freshness;
        this.blocker = blocker;
        this.review_status = review_status;
        this.diff_scope = diff_scope;
        this.completed = completed;
        this.reconciled = reconciled;
    }

    /** Create a lane context that is already reconciled (no further action needed). */
    public static LaneContext reconciled(String lane_id) {
        return new LaneContext(
                lane_id, 0, Duration.ZERO, LaneBlocker.NONE, ReviewStatus.PENDING, DiffScope.FULL, true, true);
    }

    public String lane_id() {
        return lane_id;
    }

    public int green_level() {
        return green_level;
    }

    public Duration branch_freshness() {
        return branch_freshness;
    }

    public LaneBlocker blocker() {
        return blocker;
    }

    public ReviewStatus review_status() {
        return review_status;
    }

    public DiffScope diff_scope() {
        return diff_scope;
    }

    public boolean completed() {
        return completed;
    }

    public boolean reconciled() {
        return reconciled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LaneContext other)) {
            return false;
        }
        return green_level == other.green_level
                && completed == other.completed
                && reconciled == other.reconciled
                && Objects.equals(lane_id, other.lane_id)
                && Objects.equals(branch_freshness, other.branch_freshness)
                && blocker == other.blocker
                && review_status == other.review_status
                && diff_scope == other.diff_scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                lane_id, green_level, branch_freshness, blocker, review_status, diff_scope, completed, reconciled);
    }
}
