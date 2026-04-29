package org.jclaude.runtime.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Recovery recipe registry and dispatch. Mirrors the Rust {@code recovery_recipes} module. */
public final class RecoveryRecipes {

    private RecoveryRecipes() {}

    /** Returns the known recovery recipe for {@code scenario}. */
    public static RecoveryRecipe recipe_for(FailureScenario scenario) {
        return switch (scenario) {
            case TRUST_PROMPT_UNRESOLVED -> new RecoveryRecipe(
                    scenario, List.of(new RecoveryStep.AcceptTrustPrompt()), 1, EscalationPolicy.ALERT_HUMAN);
            case PROMPT_MISDELIVERY -> new RecoveryRecipe(
                    scenario, List.of(new RecoveryStep.RedirectPromptToAgent()), 1, EscalationPolicy.ALERT_HUMAN);
            case STALE_BRANCH -> new RecoveryRecipe(
                    scenario,
                    List.of(new RecoveryStep.RebaseBranch(), new RecoveryStep.CleanBuild()),
                    1,
                    EscalationPolicy.ALERT_HUMAN);
            case COMPILE_RED_CROSS_CRATE -> new RecoveryRecipe(
                    scenario, List.of(new RecoveryStep.CleanBuild()), 1, EscalationPolicy.ALERT_HUMAN);
            case MCP_HANDSHAKE_FAILURE -> new RecoveryRecipe(
                    scenario, List.of(new RecoveryStep.RetryMcpHandshake(5_000)), 1, EscalationPolicy.ABORT);
            case PARTIAL_PLUGIN_STARTUP -> new RecoveryRecipe(
                    scenario,
                    List.of(new RecoveryStep.RestartPlugin("stalled"), new RecoveryStep.RetryMcpHandshake(3_000)),
                    1,
                    EscalationPolicy.LOG_AND_CONTINUE);
            case PROVIDER_FAILURE -> new RecoveryRecipe(
                    scenario, List.of(new RecoveryStep.RestartWorker()), 1, EscalationPolicy.ALERT_HUMAN);
        };
    }

    /** Attempts automatic recovery for {@code scenario} using {@code ctx} for state and simulation. */
    public static RecoveryResult attempt_recovery(FailureScenario scenario, RecoveryContext ctx) {
        RecoveryRecipe recipe = recipe_for(scenario);
        int current = ctx.attempts_internal().getOrDefault(scenario, 0);

        if (current >= recipe.max_attempts()) {
            RecoveryResult result = new RecoveryResult.EscalationRequired(
                    "max recovery attempts (" + recipe.max_attempts() + ") exceeded for " + scenario.display());
            ctx.push_event_internal(new RecoveryEvent.RecoveryAttempted(scenario, recipe, result));
            ctx.push_event_internal(new RecoveryEvent.Escalated());
            return result;
        }

        ctx.attempts_internal().put(scenario, current + 1);

        Optional<Integer> fail_index = ctx.fail_at_step_internal();
        List<RecoveryStep> executed = new ArrayList<>();
        boolean failed = false;

        for (int i = 0; i < recipe.steps().size(); i++) {
            if (fail_index.isPresent() && fail_index.get() == i) {
                failed = true;
                break;
            }
            executed.add(recipe.steps().get(i));
        }

        RecoveryResult result;
        if (failed) {
            List<RecoveryStep> remaining = new ArrayList<>(
                    recipe.steps().subList(executed.size(), recipe.steps().size()));
            if (executed.isEmpty()) {
                result = new RecoveryResult.EscalationRequired(
                        "recovery failed at first step for " + scenario.display());
            } else {
                result = new RecoveryResult.PartialRecovery(executed, remaining);
            }
        } else {
            result = new RecoveryResult.Recovered(recipe.steps().size());
        }

        ctx.push_event_internal(new RecoveryEvent.RecoveryAttempted(scenario, recipe, result));

        if (result instanceof RecoveryResult.Recovered) {
            ctx.push_event_internal(new RecoveryEvent.RecoverySucceeded());
        } else if (result instanceof RecoveryResult.PartialRecovery) {
            ctx.push_event_internal(new RecoveryEvent.RecoveryFailed());
        } else if (result instanceof RecoveryResult.EscalationRequired) {
            ctx.push_event_internal(new RecoveryEvent.Escalated());
        }

        return result;
    }
}
