package org.jclaude.runtime.conversation;

import java.util.List;

/** Minimal streaming API contract required by {@link ConversationRuntime}. */
@FunctionalInterface
public interface ApiClient {

    /**
     * Sends {@code request} to the upstream provider and returns the buffered list of
     * {@link AssistantEvent}s that compose the assistant turn. The returned list is
     * expected to terminate with {@link AssistantEvent.MessageStop}.
     */
    List<AssistantEvent> stream(ApiRequest request);

    /**
     * Variant that accepts a {@link ProgressListener}. Adapters that drive a real streaming
     * transport override this to push per-chunk signals to {@code listener} as they arrive.
     * The default delegates to {@link #stream(ApiRequest)} so lambda-based test clients keep
     * working unchanged.
     */
    default List<AssistantEvent> stream(ApiRequest request, ProgressListener listener) {
        return stream(request);
    }
}
