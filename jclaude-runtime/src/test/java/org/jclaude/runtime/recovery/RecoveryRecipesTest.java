package org.jclaude.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryRecipesTest {

    @Test
    void each_scenario_has_a_matching_recipe() {
        for (FailureScenario scenario : FailureScenario.all()) {
            RecoveryRecipe recipe = RecoveryRecipes.recipe_for(scenario);
            assertThat(recipe.scenario())
                    .as("recipe scenario should match requested scenario")
                    .isEqualTo(scenario);
            assertThat(recipe.steps())
                    .as("recipe for %s should have at least one step", scenario)
                    .isNotEmpty();
            assertThat(recipe.max_attempts())
                    .as("recipe for %s should allow at least one attempt", scenario)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void successful_recovery_returns_recovered_and_emits_events() {
        RecoveryContext ctx = new RecoveryContext();
        FailureScenario scenario = FailureScenario.TRUST_PROMPT_UNRESOLVED;

        RecoveryResult result = RecoveryRecipes.attempt_recovery(scenario, ctx);

        assertThat(result).isEqualTo(new RecoveryResult.Recovered(1));
        assertThat(ctx.events()).hasSize(2);
        assertThat(ctx.events().get(0)).isInstanceOfSatisfying(RecoveryEvent.RecoveryAttempted.class, attempted -> {
            assertThat(attempted.scenario()).isEqualTo(FailureScenario.TRUST_PROMPT_UNRESOLVED);
            assertThat(attempted.result())
                    .isInstanceOfSatisfying(
                            RecoveryResult.Recovered.class,
                            recovered -> assertThat(recovered.steps_taken()).isEqualTo(1));
        });
        assertThat(ctx.events().get(1)).isInstanceOf(RecoveryEvent.RecoverySucceeded.class);
    }

    @Test
    void escalation_after_max_attempts_exceeded() {
        RecoveryContext ctx = new RecoveryContext();
        FailureScenario scenario = FailureScenario.PROMPT_MISDELIVERY;

        RecoveryResult first = RecoveryRecipes.attempt_recovery(scenario, ctx);
        assertThat(first).isInstanceOf(RecoveryResult.Recovered.class);

        RecoveryResult second = RecoveryRecipes.attempt_recovery(scenario, ctx);

        assertThat(second).isInstanceOfSatisfying(RecoveryResult.EscalationRequired.class, escalation -> assertThat(
                        escalation.reason())
                .contains("max recovery attempts"));
        assertThat(ctx.attempt_count(scenario)).isEqualTo(1);
        assertThat(ctx.events()).anyMatch(e -> e instanceof RecoveryEvent.Escalated);
    }

    @Test
    void partial_recovery_when_step_fails_midway() {
        RecoveryContext ctx = new RecoveryContext().with_fail_at_step(1);
        FailureScenario scenario = FailureScenario.PARTIAL_PLUGIN_STARTUP;

        RecoveryResult result = RecoveryRecipes.attempt_recovery(scenario, ctx);

        assertThat(result).isInstanceOfSatisfying(RecoveryResult.PartialRecovery.class, partial -> {
            assertThat(partial.recovered()).hasSize(1);
            assertThat(partial.remaining()).hasSize(1);
            assertThat(partial.recovered().get(0)).isInstanceOf(RecoveryStep.RestartPlugin.class);
            assertThat(partial.remaining().get(0)).isInstanceOf(RecoveryStep.RetryMcpHandshake.class);
        });
        assertThat(ctx.events()).anyMatch(e -> e instanceof RecoveryEvent.RecoveryFailed);
    }

    @Test
    void first_step_failure_escalates_immediately() {
        RecoveryContext ctx = new RecoveryContext().with_fail_at_step(0);
        FailureScenario scenario = FailureScenario.COMPILE_RED_CROSS_CRATE;

        RecoveryResult result = RecoveryRecipes.attempt_recovery(scenario, ctx);

        assertThat(result).isInstanceOfSatisfying(RecoveryResult.EscalationRequired.class, escalation -> assertThat(
                        escalation.reason())
                .contains("failed at first step"));
        assertThat(ctx.events()).anyMatch(e -> e instanceof RecoveryEvent.Escalated);
    }

    @Test
    void emitted_events_include_structured_attempt_data() throws Exception {
        RecoveryContext ctx = new RecoveryContext();
        FailureScenario scenario = FailureScenario.MCP_HANDSHAKE_FAILURE;

        RecoveryRecipes.attempt_recovery(scenario, ctx);

        RecoveryEvent attempted = ctx.events().stream()
                .filter(e -> e instanceof RecoveryEvent.RecoveryAttempted)
                .findFirst()
                .orElseThrow(() -> new AssertionError("should have emitted RecoveryAttempted event"));

        assertThat(attempted).isInstanceOfSatisfying(RecoveryEvent.RecoveryAttempted.class, evt -> {
            assertThat(evt.scenario()).isEqualTo(scenario);
            assertThat(evt.recipe().scenario()).isEqualTo(scenario);
            assertThat(evt.recipe().steps()).isNotEmpty();
            assertThat(evt.result()).isInstanceOf(RecoveryResult.Recovered.class);
        });

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(ctx.events().get(0));
        assertThat(json).as("serialized event should contain scenario name").contains("mcp_handshake_failure");
    }

    @Test
    void recovery_context_tracks_attempts_per_scenario() {
        RecoveryContext ctx = new RecoveryContext();

        assertThat(ctx.attempt_count(FailureScenario.STALE_BRANCH)).isZero();
        RecoveryRecipes.attempt_recovery(FailureScenario.STALE_BRANCH, ctx);

        assertThat(ctx.attempt_count(FailureScenario.STALE_BRANCH)).isEqualTo(1);
        assertThat(ctx.attempt_count(FailureScenario.PROMPT_MISDELIVERY)).isZero();
    }

    @Test
    void stale_branch_recipe_has_rebase_then_clean_build() {
        RecoveryRecipe recipe = RecoveryRecipes.recipe_for(FailureScenario.STALE_BRANCH);

        assertThat(recipe.steps()).hasSize(2);
        assertThat(recipe.steps().get(0)).isEqualTo(new RecoveryStep.RebaseBranch());
        assertThat(recipe.steps().get(1)).isEqualTo(new RecoveryStep.CleanBuild());
    }

    @Test
    void partial_plugin_startup_recipe_has_restart_then_handshake() {
        RecoveryRecipe recipe = RecoveryRecipes.recipe_for(FailureScenario.PARTIAL_PLUGIN_STARTUP);

        assertThat(recipe.steps()).hasSize(2);
        assertThat(recipe.steps().get(0)).isInstanceOf(RecoveryStep.RestartPlugin.class);
        assertThat(recipe.steps().get(1))
                .isInstanceOfSatisfying(
                        RecoveryStep.RetryMcpHandshake.class,
                        handshake -> assertThat(handshake.timeout()).isEqualTo(3_000));
        assertThat(recipe.escalation_policy()).isEqualTo(EscalationPolicy.LOG_AND_CONTINUE);
    }

    @Test
    void failure_scenario_display_all_variants() {
        List<FailureScenario> scenarios = List.of(
                FailureScenario.TRUST_PROMPT_UNRESOLVED,
                FailureScenario.PROMPT_MISDELIVERY,
                FailureScenario.STALE_BRANCH,
                FailureScenario.COMPILE_RED_CROSS_CRATE,
                FailureScenario.MCP_HANDSHAKE_FAILURE,
                FailureScenario.PARTIAL_PLUGIN_STARTUP);
        List<String> expected = List.of(
                "trust_prompt_unresolved",
                "prompt_misdelivery",
                "stale_branch",
                "compile_red_cross_crate",
                "mcp_handshake_failure",
                "partial_plugin_startup");

        for (int i = 0; i < scenarios.size(); i++) {
            assertThat(scenarios.get(i).display()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void multi_step_success_reports_correct_steps_taken() {
        RecoveryContext ctx = new RecoveryContext();
        FailureScenario scenario = FailureScenario.STALE_BRANCH;

        RecoveryResult result = RecoveryRecipes.attempt_recovery(scenario, ctx);

        assertThat(result).isEqualTo(new RecoveryResult.Recovered(2));
    }

    @Test
    void mcp_handshake_recipe_uses_abort_escalation_policy() {
        RecoveryRecipe recipe = RecoveryRecipes.recipe_for(FailureScenario.MCP_HANDSHAKE_FAILURE);

        assertThat(recipe.escalation_policy()).isEqualTo(EscalationPolicy.ABORT);
        assertThat(recipe.max_attempts()).isEqualTo(1);
    }

    @Test
    void worker_failure_kind_maps_to_failure_scenario() {
        assertThat(FailureScenario.from_worker_failure_kind(WorkerFailureKind.TRUST_GATE))
                .isEqualTo(FailureScenario.TRUST_PROMPT_UNRESOLVED);
        assertThat(FailureScenario.from_worker_failure_kind(WorkerFailureKind.PROMPT_DELIVERY))
                .isEqualTo(FailureScenario.PROMPT_MISDELIVERY);
        assertThat(FailureScenario.from_worker_failure_kind(WorkerFailureKind.PROTOCOL))
                .isEqualTo(FailureScenario.MCP_HANDSHAKE_FAILURE);
        assertThat(FailureScenario.from_worker_failure_kind(WorkerFailureKind.PROVIDER))
                .isEqualTo(FailureScenario.PROVIDER_FAILURE);
    }

    @Test
    void provider_failure_recipe_uses_restart_worker_step() {
        RecoveryRecipe recipe = RecoveryRecipes.recipe_for(FailureScenario.PROVIDER_FAILURE);

        assertThat(recipe.scenario()).isEqualTo(FailureScenario.PROVIDER_FAILURE);
        assertThat(recipe.steps()).contains(new RecoveryStep.RestartWorker());
        assertThat(recipe.escalation_policy()).isEqualTo(EscalationPolicy.ALERT_HUMAN);
        assertThat(recipe.max_attempts()).isEqualTo(1);
    }

    @Test
    void provider_failure_recovery_attempt_succeeds_then_escalates() {
        RecoveryContext ctx = new RecoveryContext();
        FailureScenario scenario = FailureScenario.PROVIDER_FAILURE;

        RecoveryResult first = RecoveryRecipes.attempt_recovery(scenario, ctx);
        assertThat(first).isInstanceOf(RecoveryResult.Recovered.class);

        RecoveryResult second = RecoveryRecipes.attempt_recovery(scenario, ctx);
        assertThat(second).isInstanceOf(RecoveryResult.EscalationRequired.class);
        assertThat(ctx.events()).anyMatch(e -> e instanceof RecoveryEvent.Escalated);
    }
}
