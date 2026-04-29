package org.jclaude.api.providers.anthropic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jclaude.api.types.MessageResponse;
import org.jclaude.api.types.Usage;

/**
 * Lightweight prompt-cache instrumentation.
 *
 * <p>Mirrors a small subset of the Rust {@code PromptCache} struct from
 * {@code crates/api/src/prompt_cache.rs}. The full Rust type also implements
 * a completion cache and unexpected-cache-break detection — this Java port
 * focuses on the response-side bookkeeping that matters for telemetry: the
 * latest {@code cache_creation_input_tokens} and {@code cache_read_input_tokens}
 * values, plus a tracked-request counter so callers can surface cache hit
 * rates to users.
 */
public final class PromptCache {

    private final String session_id;
    private final AtomicLong tracked_requests = new AtomicLong();
    private final AtomicLong total_cache_creation_input_tokens = new AtomicLong();
    private final AtomicLong total_cache_read_input_tokens = new AtomicLong();
    private final AtomicReference<Long> last_cache_creation_input_tokens = new AtomicReference<>();
    private final AtomicReference<Long> last_cache_read_input_tokens = new AtomicReference<>();

    public PromptCache(String session_id) {
        this.session_id = session_id == null ? "" : session_id;
    }

    public String session_id() {
        return session_id;
    }

    /** Record the prompt-cache token counters from a non-streaming response. */
    public void record_response(MessageResponse response) {
        if (response == null) {
            return;
        }
        record_usage(response.usage());
    }

    /** Record the prompt-cache token counters from a streaming usage update. */
    public void record_usage(Usage usage) {
        if (usage == null) {
            return;
        }
        tracked_requests.incrementAndGet();
        long creation = usage.cache_creation_input_tokens();
        long read = usage.cache_read_input_tokens();
        total_cache_creation_input_tokens.addAndGet(creation);
        total_cache_read_input_tokens.addAndGet(read);
        last_cache_creation_input_tokens.set(creation);
        last_cache_read_input_tokens.set(read);
    }

    public PromptCacheStats stats() {
        return new PromptCacheStats(
                tracked_requests.get(),
                total_cache_creation_input_tokens.get(),
                total_cache_read_input_tokens.get(),
                last_cache_creation_input_tokens.get(),
                last_cache_read_input_tokens.get());
    }

    /** Snapshot of the prompt-cache instrumentation counters. */
    public record PromptCacheStats(
            long tracked_requests,
            long total_cache_creation_input_tokens,
            long total_cache_read_input_tokens,
            Long last_cache_creation_input_tokens,
            Long last_cache_read_input_tokens) {}
}
