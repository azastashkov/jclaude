package org.jclaude.api.sse;

import java.util.Optional;

/**
 * High level Server-Sent Event with the SSE-frame fields exposed to callers.
 *
 * <p>The {@code name} corresponds to the {@code event:} field, {@code data} is
 * the joined payload from one or more {@code data:} lines, and {@code id} is
 * the optional {@code id:} field. The {@link #parsed} method is provided as a
 * convenience for the Anthropic streaming protocol where {@code data} is a
 * JSON document that deserialises into {@link org.jclaude.api.types.StreamEvent}.
 */
public record SseEvent(String name, String data, String id) {

    public SseEvent {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
    }

    public Optional<String> nameOptional() {
        return Optional.ofNullable(name);
    }

    public Optional<String> idOptional() {
        return Optional.ofNullable(id);
    }
}
