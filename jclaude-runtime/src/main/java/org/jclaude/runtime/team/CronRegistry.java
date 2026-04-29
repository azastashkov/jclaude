package org.jclaude.runtime.team;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory registry tracking cron-style scheduled prompts. Mirrors the Rust {@code CronRegistry}. */
public final class CronRegistry {

    private static final CronParser UNIX_PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private final Object lock = new Object();
    private final Map<String, CronEntry> entries = new HashMap<>();
    private long counter;

    public CronRegistry() {}

    public CronEntry create(String schedule, String prompt, String description) {
        synchronized (lock) {
            counter += 1;
            long ts = now_secs();
            String cron_id = String.format("cron_%08x_%d", ts, counter);
            CronEntry entry = new CronEntry(cron_id, schedule, prompt, description, true, ts, ts, null, 0);
            entries.put(cron_id, entry);
            return entry.snapshot();
        }
    }

    public Optional<CronEntry> get(String cron_id) {
        synchronized (lock) {
            CronEntry entry = entries.get(cron_id);
            return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
        }
    }

    public List<CronEntry> list(boolean enabled_only) {
        synchronized (lock) {
            List<CronEntry> result = new ArrayList<>();
            for (CronEntry entry : entries.values()) {
                if (!enabled_only || entry.enabled()) {
                    result.add(entry.snapshot());
                }
            }
            return result;
        }
    }

    public CronEntry delete(String cron_id) {
        synchronized (lock) {
            CronEntry removed = entries.remove(cron_id);
            if (removed == null) {
                throw new CronRegistryException("cron not found: " + cron_id);
            }
            return removed.snapshot();
        }
    }

    /** Disables an entry without removing it. */
    public void disable(String cron_id) {
        synchronized (lock) {
            CronEntry entry = entries.get(cron_id);
            if (entry == null) {
                throw new CronRegistryException("cron not found: " + cron_id);
            }
            entry.set_enabled_internal(false);
            entry.set_updated_at_internal(now_secs());
        }
    }

    /** Records that a cron run executed. */
    public void record_run(String cron_id) {
        synchronized (lock) {
            CronEntry entry = entries.get(cron_id);
            if (entry == null) {
                throw new CronRegistryException("cron not found: " + cron_id);
            }
            long ts = now_secs();
            entry.set_last_run_at_internal(ts);
            entry.increment_run_count_internal();
            entry.set_updated_at_internal(ts);
        }
    }

    public int len() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public boolean is_empty() {
        return len() == 0;
    }

    /** Validates that {@code schedule} parses as a UNIX (5-field) cron expression. */
    public static boolean is_valid_unix_schedule(String schedule) {
        try {
            UNIX_PARSER.parse(schedule);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static long now_secs() {
        return Instant.now().getEpochSecond();
    }
}
