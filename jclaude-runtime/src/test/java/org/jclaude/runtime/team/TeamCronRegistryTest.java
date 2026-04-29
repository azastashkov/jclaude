package org.jclaude.runtime.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamCronRegistryTest {

    // ── Team tests ──────────────────────────────────────

    @Test
    void creates_and_retrieves_team() {
        TeamRegistry registry = new TeamRegistry();
        Team team = registry.create("Alpha Squad", List.of("task_001", "task_002"));
        assertThat(team.name()).isEqualTo("Alpha Squad");
        assertThat(team.task_ids()).hasSize(2);
        assertThat(team.status()).isEqualTo(TeamStatus.CREATED);

        Team fetched = registry.get(team.team_id()).orElseThrow();
        assertThat(fetched.team_id()).isEqualTo(team.team_id());
    }

    @Test
    void lists_and_deletes_teams() {
        TeamRegistry registry = new TeamRegistry();
        Team t1 = registry.create("Team A", List.of());
        Team t2 = registry.create("Team B", List.of());

        List<Team> all = registry.list();
        assertThat(all).hasSize(2);

        Team deleted = registry.delete(t1.team_id());
        assertThat(deleted.status()).isEqualTo(TeamStatus.DELETED);

        Team still_there = registry.get(t1.team_id()).orElseThrow();
        assertThat(still_there.status()).isEqualTo(TeamStatus.DELETED);

        registry.remove(t2.team_id());
        assertThat(registry.len()).isEqualTo(1);
    }

    @Test
    void rejects_missing_team_operations() {
        TeamRegistry registry = new TeamRegistry();
        assertThatThrownBy(() -> registry.delete("nonexistent")).isInstanceOf(TeamRegistryException.class);
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    // ── Cron tests ──────────────────────────────────────

    @Test
    void creates_and_retrieves_cron() {
        CronRegistry registry = new CronRegistry();
        CronEntry entry = registry.create("0 * * * *", "Check status", "hourly check");
        assertThat(entry.schedule()).isEqualTo("0 * * * *");
        assertThat(entry.prompt()).isEqualTo("Check status");
        assertThat(entry.enabled()).isTrue();
        assertThat(entry.run_count()).isZero();
        assertThat(entry.last_run_at()).isEmpty();

        CronEntry fetched = registry.get(entry.cron_id()).orElseThrow();
        assertThat(fetched.cron_id()).isEqualTo(entry.cron_id());
    }

    @Test
    void lists_with_enabled_filter() {
        CronRegistry registry = new CronRegistry();
        CronEntry c1 = registry.create("* * * * *", "Task 1", null);
        CronEntry c2 = registry.create("0 * * * *", "Task 2", null);
        registry.disable(c1.cron_id());

        List<CronEntry> all = registry.list(false);
        assertThat(all).hasSize(2);

        List<CronEntry> enabled_only = registry.list(true);
        assertThat(enabled_only).hasSize(1);
        assertThat(enabled_only.get(0).cron_id()).isEqualTo(c2.cron_id());
    }

    @Test
    void deletes_cron_entry() {
        CronRegistry registry = new CronRegistry();
        CronEntry entry = registry.create("* * * * *", "To delete", null);
        CronEntry deleted = registry.delete(entry.cron_id());
        assertThat(deleted.cron_id()).isEqualTo(entry.cron_id());
        assertThat(registry.get(entry.cron_id())).isEmpty();
        assertThat(registry.is_empty()).isTrue();
    }

    @Test
    void records_cron_runs() {
        CronRegistry registry = new CronRegistry();
        CronEntry entry = registry.create("*/5 * * * *", "Recurring", null);
        registry.record_run(entry.cron_id());
        registry.record_run(entry.cron_id());

        CronEntry fetched = registry.get(entry.cron_id()).orElseThrow();
        assertThat(fetched.run_count()).isEqualTo(2);
        assertThat(fetched.last_run_at()).isPresent();
    }

    @Test
    void rejects_missing_cron_operations() {
        CronRegistry registry = new CronRegistry();
        assertThatThrownBy(() -> registry.delete("nonexistent")).isInstanceOf(CronRegistryException.class);
        assertThatThrownBy(() -> registry.disable("nonexistent")).isInstanceOf(CronRegistryException.class);
        assertThatThrownBy(() -> registry.record_run("nonexistent")).isInstanceOf(CronRegistryException.class);
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void team_status_display_all_variants() {
        List<TeamStatus> statuses =
                List.of(TeamStatus.CREATED, TeamStatus.RUNNING, TeamStatus.COMPLETED, TeamStatus.DELETED);
        List<String> expected = List.of("created", "running", "completed", "deleted");
        for (int i = 0; i < statuses.size(); i++) {
            assertThat(statuses.get(i).display()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void new_team_registry_is_empty() {
        TeamRegistry registry = new TeamRegistry();
        List<Team> teams = registry.list();
        assertThat(registry.is_empty()).isTrue();
        assertThat(registry.len()).isZero();
        assertThat(teams).isEmpty();
    }

    @Test
    void team_remove_nonexistent_returns_none() {
        TeamRegistry registry = new TeamRegistry();
        Optional<Team> removed = registry.remove("missing");
        assertThat(removed).isEmpty();
    }

    @Test
    void team_len_transitions() {
        TeamRegistry registry = new TeamRegistry();

        Team alpha = registry.create("Alpha", List.of());
        Team beta = registry.create("Beta", List.of());
        int after_create = registry.len();
        registry.remove(alpha.team_id());
        int after_first_remove = registry.len();
        registry.remove(beta.team_id());

        assertThat(after_create).isEqualTo(2);
        assertThat(after_first_remove).isEqualTo(1);
        assertThat(registry.len()).isZero();
        assertThat(registry.is_empty()).isTrue();
    }

    @Test
    void cron_list_all_disabled_returns_empty_for_enabled_only() {
        CronRegistry registry = new CronRegistry();
        CronEntry first = registry.create("* * * * *", "Task 1", null);
        CronEntry second = registry.create("0 * * * *", "Task 2", null);
        registry.disable(first.cron_id());
        registry.disable(second.cron_id());

        List<CronEntry> enabled_only = registry.list(true);
        List<CronEntry> all_entries = registry.list(false);

        assertThat(enabled_only).isEmpty();
        assertThat(all_entries).hasSize(2);
    }

    @Test
    void cron_create_without_description() {
        CronRegistry registry = new CronRegistry();

        CronEntry entry = registry.create("*/15 * * * *", "Check health", null);

        assertThat(entry.cron_id()).startsWith("cron_");
        assertThat(entry.description()).isEmpty();
        assertThat(entry.enabled()).isTrue();
        assertThat(entry.run_count()).isZero();
        assertThat(entry.last_run_at()).isEmpty();
    }

    @Test
    void new_cron_registry_is_empty() {
        CronRegistry registry = new CronRegistry();

        List<CronEntry> enabled_only = registry.list(true);
        List<CronEntry> all_entries = registry.list(false);

        assertThat(registry.is_empty()).isTrue();
        assertThat(registry.len()).isZero();
        assertThat(enabled_only).isEmpty();
        assertThat(all_entries).isEmpty();
    }

    @Test
    void cron_record_run_updates_timestamp_and_counter() {
        CronRegistry registry = new CronRegistry();
        CronEntry entry = registry.create("*/5 * * * *", "Recurring", null);

        registry.record_run(entry.cron_id());
        registry.record_run(entry.cron_id());
        CronEntry fetched = registry.get(entry.cron_id()).orElseThrow();

        assertThat(fetched.run_count()).isEqualTo(2);
        assertThat(fetched.last_run_at()).isPresent();
        assertThat(fetched.updated_at()).isGreaterThanOrEqualTo(entry.updated_at());
    }

    @Test
    void cron_disable_updates_timestamp() {
        CronRegistry registry = new CronRegistry();
        CronEntry entry = registry.create("0 0 * * *", "Nightly", null);

        registry.disable(entry.cron_id());
        CronEntry fetched = registry.get(entry.cron_id()).orElseThrow();

        assertThat(fetched.enabled()).isFalse();
        assertThat(fetched.updated_at()).isGreaterThanOrEqualTo(entry.updated_at());
    }
}
