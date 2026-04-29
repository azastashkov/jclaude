package org.jclaude.telemetry;

import java.util.ArrayList;
import java.util.List;

/** In-process buffering sink. Used by tests and short-lived sessions. */
public final class MemoryTelemetrySink implements TelemetrySink {

    private final List<TelemetryEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(TelemetryEvent event) {
        events.add(event);
    }

    public synchronized List<TelemetryEvent> events() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}
