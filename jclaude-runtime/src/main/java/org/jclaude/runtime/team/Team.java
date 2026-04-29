package org.jclaude.runtime.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory representation of a team coordinating multiple tasks. */
public final class Team {

    private final String team_id;
    private final String name;
    private final List<String> task_ids;
    private TeamStatus status;
    private final long created_at;
    private long updated_at;

    Team(String team_id, String name, List<String> task_ids, TeamStatus status, long created_at, long updated_at) {
        this.team_id = team_id;
        this.name = name;
        this.task_ids = new ArrayList<>(task_ids);
        this.status = status;
        this.created_at = created_at;
        this.updated_at = updated_at;
    }

    private Team(Team other) {
        this.team_id = other.team_id;
        this.name = other.name;
        this.task_ids = new ArrayList<>(other.task_ids);
        this.status = other.status;
        this.created_at = other.created_at;
        this.updated_at = other.updated_at;
    }

    public Team snapshot() {
        return new Team(this);
    }

    @JsonProperty("team_id")
    public String team_id() {
        return team_id;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("task_ids")
    public List<String> task_ids() {
        return List.copyOf(task_ids);
    }

    @JsonProperty("status")
    public TeamStatus status() {
        return status;
    }

    @JsonProperty("created_at")
    public long created_at() {
        return created_at;
    }

    @JsonProperty("updated_at")
    public long updated_at() {
        return updated_at;
    }

    void set_status_internal(TeamStatus status) {
        this.status = status;
    }

    void set_updated_at_internal(long updated_at) {
        this.updated_at = updated_at;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Team other)) {
            return false;
        }
        return created_at == other.created_at
                && updated_at == other.updated_at
                && team_id.equals(other.team_id)
                && Objects.equals(name, other.name)
                && task_ids.equals(other.task_ids)
                && status == other.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(team_id, name, task_ids, status, created_at, updated_at);
    }
}
