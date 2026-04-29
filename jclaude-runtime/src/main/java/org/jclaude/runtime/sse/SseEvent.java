package org.jclaude.runtime.sse;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Server-Sent Event as exposed by {@link IncrementalSseParser}.
 *
 * <p>This is the runtime-side view of an SSE frame. Unlike the API module's
 * {@code SseEvent}, this record also surfaces the optional {@code retry:}
 * reconnection hint because the runtime forwards SSE traffic through proxies
 * and tooling layers that need to honour it.
 */
public record SseEvent(String event, String data, String id, Long retry) {

    public SseEvent {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
    }

    public Optional<String> eventOptional() {
        return Optional.ofNullable(event);
    }

    public Optional<String> idOptional() {
        return Optional.ofNullable(id);
    }

    public OptionalLong retryOptional() {
        return retry == null ? OptionalLong.empty() : OptionalLong.of(retry);
    }
}
