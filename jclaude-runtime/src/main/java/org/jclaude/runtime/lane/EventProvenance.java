package org.jclaude.runtime.lane;

import java.util.Locale;

/** Provenance label for lane events. */
public enum EventProvenance {
    LIVE_LANE,
    TEST,
    HEALTHCHECK,
    REPLAY,
    TRANSPORT;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
