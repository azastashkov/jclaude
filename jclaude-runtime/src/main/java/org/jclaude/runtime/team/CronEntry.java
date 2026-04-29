package org.jclaude.runtime.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.Optional;

/** In-memory representation of a cron schedule entry. */
public final class CronEntry {

    private final String cron_id;
    private final String schedule;
    private final String prompt;
    private final String description;
    private boolean enabled;
    private final long created_at;
    private long updated_at;
    private Long last_run_at;
    private long run_count;

    CronEntry(
            String cron_id,
            String schedule,
            String prompt,
            String description,
            boolean enabled,
            long created_at,
            long updated_at,
            Long last_run_at,
            long run_count) {
        this.cron_id = cron_id;
        this.schedule = schedule;
        this.prompt = prompt;
        this.description = description;
        this.enabled = enabled;
        this.created_at = created_at;
        this.updated_at = updated_at;
        this.last_run_at = last_run_at;
        this.run_count = run_count;
    }

    private CronEntry(CronEntry other) {
        this(
                other.cron_id,
                other.schedule,
                other.prompt,
                other.description,
                other.enabled,
                other.created_at,
                other.updated_at,
                other.last_run_at,
                other.run_count);
    }

    public CronEntry snapshot() {
        return new CronEntry(this);
    }

    @JsonProperty("cron_id")
    public String cron_id() {
        return cron_id;
    }

    @JsonProperty("schedule")
    public String schedule() {
        return schedule;
    }

    @JsonProperty("prompt")
    public String prompt() {
        return prompt;
    }

    @JsonProperty("description")
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    @JsonProperty("enabled")
    public boolean enabled() {
        return enabled;
    }

    @JsonProperty("created_at")
    public long created_at() {
        return created_at;
    }

    @JsonProperty("updated_at")
    public long updated_at() {
        return updated_at;
    }

    @JsonProperty("last_run_at")
    public Optional<Long> last_run_at() {
        return Optional.ofNullable(last_run_at);
    }

    @JsonProperty("run_count")
    public long run_count() {
        return run_count;
    }

    void set_enabled_internal(boolean enabled) {
        this.enabled = enabled;
    }

    void set_updated_at_internal(long updated_at) {
        this.updated_at = updated_at;
    }

    void set_last_run_at_internal(Long last_run_at) {
        this.last_run_at = last_run_at;
    }

    void increment_run_count_internal() {
        this.run_count += 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CronEntry other)) {
            return false;
        }
        return enabled == other.enabled
                && created_at == other.created_at
                && updated_at == other.updated_at
                && run_count == other.run_count
                && cron_id.equals(other.cron_id)
                && schedule.equals(other.schedule)
                && prompt.equals(other.prompt)
                && Objects.equals(description, other.description)
                && Objects.equals(last_run_at, other.last_run_at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                cron_id, schedule, prompt, description, enabled, created_at, updated_at, last_run_at, run_count);
    }
}
