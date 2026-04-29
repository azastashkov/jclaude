package org.jclaude.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory store backing the Phase 1 {@code TodoWrite} stub. Phase 4 wires in the real persistent
 * registry from the upstream Rust implementation; until then we just remember the most recently
 * written list so tests can observe round-trip behavior.
 *
 * <p>Each todo is kept as a {@code Map<String, Object>} so the runtime does not need to commit to
 * a fixed schema for this stub.
 */
public final class TodoStore {

    private static final TodoStore GLOBAL = new TodoStore();

    private List<Map<String, Object>> latest = new ArrayList<>();

    /** Returns the process-wide singleton instance. */
    public static TodoStore global() {
        return GLOBAL;
    }

    /** Stores a copy of {@code todos} and returns the previously stored list. */
    public synchronized List<Map<String, Object>> write(List<Map<String, Object>> todos) {
        List<Map<String, Object>> previous = latest;
        latest = new ArrayList<>(todos == null ? List.of() : todos);
        return previous;
    }

    /** Returns a copy of the most recently stored list. */
    public synchronized List<Map<String, Object>> snapshot() {
        return new ArrayList<>(latest);
    }

    /** Resets the store; primarily useful for tests. */
    public synchronized void clear() {
        latest = new ArrayList<>();
    }
}
