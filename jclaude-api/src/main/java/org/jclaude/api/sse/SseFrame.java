package org.jclaude.api.sse;

/**
 * Low level Server-Sent Event frame — the raw, untrimmed text between two
 * frame separators (a blank line in the SSE wire format).
 *
 * <p>{@link SseStreamReader} produces {@code SseFrame} instances by splitting
 * the byte stream on {@code \n\n} or {@code \r\n\r\n}; each frame is then
 * parsed into an {@link SseEvent} via {@link SseStreamReader#parseFrame(SseFrame)}.
 */
public record SseFrame(String text) {

    public SseFrame {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
