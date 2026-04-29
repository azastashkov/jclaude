package org.jclaude.tools;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide flag toggled by the {@code EnterPlanMode}/{@code ExitPlanMode} stubs. Phase 1 only
 * tracks a boolean — the upstream Rust port persists per-worktree overrides which will land in a
 * later phase.
 */
public final class PlanModeState {

    private static final PlanModeState GLOBAL = new PlanModeState();

    private final AtomicBoolean planMode = new AtomicBoolean(false);

    public static PlanModeState global() {
        return GLOBAL;
    }

    public boolean is_plan_mode() {
        return planMode.get();
    }

    public void set_plan_mode(boolean value) {
        planMode.set(value);
    }
}
