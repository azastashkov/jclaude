package org.jclaude.runtime.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory registry of {@link Team} instances. Mirrors the Rust {@code TeamRegistry}. */
public final class TeamRegistry {

    private final Object lock = new Object();
    private final Map<String, Team> teams = new HashMap<>();
    private long counter;

    public TeamRegistry() {}

    public Team create(String name, List<String> task_ids) {
        synchronized (lock) {
            counter += 1;
            long ts = now_secs();
            String team_id = String.format("team_%08x_%d", ts, counter);
            Team team = new Team(team_id, name, task_ids, TeamStatus.CREATED, ts, ts);
            teams.put(team_id, team);
            return team.snapshot();
        }
    }

    public Optional<Team> get(String team_id) {
        synchronized (lock) {
            Team team = teams.get(team_id);
            return team == null ? Optional.empty() : Optional.of(team.snapshot());
        }
    }

    public List<Team> list() {
        synchronized (lock) {
            List<Team> result = new ArrayList<>();
            for (Team team : teams.values()) {
                result.add(team.snapshot());
            }
            return result;
        }
    }

    /** Soft-deletes a team by marking it as deleted. */
    public Team delete(String team_id) {
        synchronized (lock) {
            Team team = teams.get(team_id);
            if (team == null) {
                throw new TeamRegistryException("team not found: " + team_id);
            }
            team.set_status_internal(TeamStatus.DELETED);
            team.set_updated_at_internal(now_secs());
            return team.snapshot();
        }
    }

    public Optional<Team> remove(String team_id) {
        synchronized (lock) {
            Team removed = teams.remove(team_id);
            return removed == null ? Optional.empty() : Optional.of(removed.snapshot());
        }
    }

    public int len() {
        synchronized (lock) {
            return teams.size();
        }
    }

    public boolean is_empty() {
        return len() == 0;
    }

    static long now_secs() {
        return Instant.now().getEpochSecond();
    }
}
