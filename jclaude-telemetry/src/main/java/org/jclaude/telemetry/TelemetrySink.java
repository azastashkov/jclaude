package org.jclaude.telemetry;

/** Drain interface for {@link TelemetryEvent}s. Implementations must be thread-safe. */
public interface TelemetrySink {

    void record(TelemetryEvent event);

    /** No-op sink used when telemetry is disabled. */
    static TelemetrySink noop() {
        return event -> {};
    }
}
