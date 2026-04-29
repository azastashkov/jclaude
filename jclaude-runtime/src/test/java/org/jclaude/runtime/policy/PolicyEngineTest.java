package org.jclaude.runtime.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static LaneContext default_context() {
        return new LaneContext(
                "lane-7", 0, Duration.ZERO, LaneBlocker.NONE, ReviewStatus.PENDING, DiffScope.FULL, false);
    }

    @Test
    void merge_to_dev_rule_fires_for_green_scoped_reviewed_lane() {
        PolicyEngine engine = new PolicyEngine(List.of(new PolicyRule(
                "merge-to-dev",
                new PolicyCondition.And(List.of(
                        new PolicyCondition.GreenAt(2),
                        new PolicyCondition.ScopedDiff(),
                        new PolicyCondition.ReviewPassed())),
                new PolicyAction.MergeToDev(),
                20)));
        LaneContext context = new LaneContext(
                "lane-7", 3, Duration.ofSeconds(5), LaneBlocker.NONE, ReviewStatus.APPROVED, DiffScope.SCOPED, false);

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions).containsExactly(new PolicyAction.MergeToDev());
    }

    @Test
    void stale_branch_rule_fires_at_threshold() {
        PolicyEngine engine = new PolicyEngine(List.of(new PolicyRule(
                "merge-forward", new PolicyCondition.StaleBranch(), new PolicyAction.MergeForward(), 10)));
        LaneContext context = new LaneContext(
                "lane-7",
                1,
                PolicyCondition.STALE_BRANCH_THRESHOLD,
                LaneBlocker.NONE,
                ReviewStatus.PENDING,
                DiffScope.FULL,
                false);

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions).containsExactly(new PolicyAction.MergeForward());
    }

    @Test
    void startup_blocked_rule_recovers_then_escalates() {
        PolicyEngine engine = new PolicyEngine(List.of(new PolicyRule(
                "startup-recovery",
                new PolicyCondition.StartupBlocked(),
                new PolicyAction.Chain(
                        List.of(new PolicyAction.RecoverOnce(), new PolicyAction.Escalate("startup remained blocked"))),
                15)));
        LaneContext context = new LaneContext(
                "lane-7", 0, Duration.ZERO, LaneBlocker.STARTUP, ReviewStatus.PENDING, DiffScope.FULL, false);

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions)
                .containsExactly(new PolicyAction.RecoverOnce(), new PolicyAction.Escalate("startup remained blocked"));
    }

    @Test
    void completed_lane_rule_closes_out_and_cleans_up() {
        PolicyEngine engine = new PolicyEngine(List.of(new PolicyRule(
                "lane-closeout",
                new PolicyCondition.LaneCompleted(),
                new PolicyAction.Chain(List.of(new PolicyAction.CloseoutLane(), new PolicyAction.CleanupSession())),
                30)));
        LaneContext context = new LaneContext(
                "lane-7", 0, Duration.ZERO, LaneBlocker.NONE, ReviewStatus.PENDING, DiffScope.FULL, true);

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions).containsExactly(new PolicyAction.CloseoutLane(), new PolicyAction.CleanupSession());
    }

    @Test
    void matching_rules_are_returned_in_priority_order_with_stable_ties() {
        PolicyEngine engine = new PolicyEngine(List.of(
                new PolicyRule(
                        "late-cleanup", new PolicyCondition.And(List.of()), new PolicyAction.CleanupSession(), 30),
                new PolicyRule("first-notify", new PolicyCondition.And(List.of()), new PolicyAction.Notify("ops"), 10),
                new PolicyRule(
                        "second-notify", new PolicyCondition.And(List.of()), new PolicyAction.Notify("review"), 10),
                new PolicyRule("merge", new PolicyCondition.And(List.of()), new PolicyAction.MergeToDev(), 20)));
        LaneContext context = default_context();

        List<PolicyAction> actions = PolicyEngine.evaluate(engine, context);

        assertThat(actions)
                .containsExactly(
                        new PolicyAction.Notify("ops"),
                        new PolicyAction.Notify("review"),
                        new PolicyAction.MergeToDev(),
                        new PolicyAction.CleanupSession());
    }

    @Test
    void combinators_handle_empty_cases_and_nested_chains() {
        PolicyEngine engine = new PolicyEngine(List.of(
                new PolicyRule(
                        "empty-and", new PolicyCondition.And(List.of()), new PolicyAction.Notify("orchestrator"), 5),
                new PolicyRule(
                        "empty-or", new PolicyCondition.Or(List.of()), new PolicyAction.Block("should not fire"), 10),
                new PolicyRule(
                        "nested",
                        new PolicyCondition.Or(List.of(
                                new PolicyCondition.StartupBlocked(),
                                new PolicyCondition.And(List.of(
                                        new PolicyCondition.GreenAt(2),
                                        new PolicyCondition.TimedOut(Duration.ofSeconds(5)))))),
                        new PolicyAction.Chain(List.of(
                                new PolicyAction.Notify("alerts"),
                                new PolicyAction.Chain(
                                        List.of(new PolicyAction.MergeForward(), new PolicyAction.CleanupSession())))),
                        15)));
        LaneContext context = new LaneContext(
                "lane-7", 2, Duration.ofSeconds(10), LaneBlocker.EXTERNAL, ReviewStatus.PENDING, DiffScope.FULL, false);

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions)
                .containsExactly(
                        new PolicyAction.Notify("orchestrator"),
                        new PolicyAction.Notify("alerts"),
                        new PolicyAction.MergeForward(),
                        new PolicyAction.CleanupSession());
    }

    @Test
    void reconciled_lane_emits_reconcile_and_cleanup() {
        PolicyEngine engine = new PolicyEngine(List.of(
                new PolicyRule(
                        "reconcile-closeout",
                        new PolicyCondition.LaneReconciled(),
                        new PolicyAction.Chain(List.of(
                                new PolicyAction.Reconcile(ReconcileReason.ALREADY_MERGED),
                                new PolicyAction.CloseoutLane(),
                                new PolicyAction.CleanupSession())),
                        5),
                new PolicyRule(
                        "generic-closeout",
                        new PolicyCondition.And(
                                List.of(new PolicyCondition.LaneCompleted(), new PolicyCondition.And(List.of()))),
                        new PolicyAction.CloseoutLane(),
                        30)));
        LaneContext context = LaneContext.reconciled("lane-9411");

        List<PolicyAction> actions = engine.evaluate(context);

        assertThat(actions)
                .containsExactly(
                        new PolicyAction.Reconcile(ReconcileReason.ALREADY_MERGED),
                        new PolicyAction.CloseoutLane(),
                        new PolicyAction.CleanupSession(),
                        new PolicyAction.CloseoutLane());
    }

    @Test
    void reconciled_context_has_correct_defaults() {
        LaneContext ctx = LaneContext.reconciled("test-lane");
        assertThat(ctx.lane_id()).isEqualTo("test-lane");
        assertThat(ctx.completed()).isTrue();
        assertThat(ctx.reconciled()).isTrue();
        assertThat(ctx.blocker()).isEqualTo(LaneBlocker.NONE);
        assertThat(ctx.green_level()).isEqualTo(0);
    }

    @Test
    void non_reconciled_lane_does_not_trigger_reconcile_rule() {
        PolicyEngine engine = new PolicyEngine(List.of(new PolicyRule(
                "reconcile-closeout",
                new PolicyCondition.LaneReconciled(),
                new PolicyAction.Reconcile(ReconcileReason.EMPTY_DIFF),
                5)));
        LaneContext context = new LaneContext(
                "lane-7", 0, Duration.ZERO, LaneBlocker.NONE, ReviewStatus.PENDING, DiffScope.FULL, true);

        List<PolicyAction> actions = engine.evaluate(context);
        assertThat(actions).isEmpty();
    }

    @Test
    void reconcile_reason_variants_are_distinct() {
        assertThat(ReconcileReason.ALREADY_MERGED).isNotEqualTo(ReconcileReason.SUPERSEDED);
        assertThat(ReconcileReason.EMPTY_DIFF).isNotEqualTo(ReconcileReason.MANUAL_CLOSE);
    }
}
