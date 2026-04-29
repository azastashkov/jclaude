package org.jclaude.runtime.sse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental SSE parser that assembles complete events from byte chunks
 * which may slice through field values, JSON payloads, or even multi-byte
 * UTF-8 sequences.
 *
 * <p>This is the Java port of the Rust {@code IncrementalSseParser} from
 * {@code crates/runtime/src/sse.rs}. The implementation operates on raw
 * bytes rather than buffered lines so that:
 * <ul>
 *   <li>Multi-byte UTF-8 characters split across chunk boundaries are
 *       reassembled correctly when the line is finally decoded.</li>
 *   <li>JSON payloads spanning many chunks (the {@code grep_chunk_assembly}
 *       mock parity scenario) are joined byte-faithfully across multiple
 *       {@code data:} lines.</li>
 * </ul>
 *
 * <p>The parser is push-driven: callers feed it bytes via
 * {@link #pushChunk(byte[])} or {@link #pushChunk(String)} and consume any
 * complete events that have become available. Trailing state is flushed by
 * {@link #finish()}.
 */
public final class IncrementalSseParser {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private String eventName;
    private final List<String> dataLines = new ArrayList<>();
    private String id;
    private Long retry;

    public IncrementalSseParser() {}

    /** Convenience overload that encodes {@code chunk} as UTF-8 bytes. */
    public List<SseEvent> pushChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        return pushChunk(chunk.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Append the raw bytes in {@code chunk} to the parser's buffer and return
     * any events that have been completed by the new data.
     */
    public List<SseEvent> pushChunk(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            buffer.write(chunk, 0, chunk.length);
        }
        List<SseEvent> events = new ArrayList<>();

        while (true) {
            byte[] bytes = buffer.toByteArray();
            int newline = -1;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == (byte) '\n') {
                    newline = i;
                    break;
                }
            }
            if (newline < 0) {
                break;
            }
            int lineLength = newline;
            // strip trailing \r if present
            if (lineLength > 0 && bytes[lineLength - 1] == (byte) '\r') {
                lineLength--;
            }
            String line = new String(bytes, 0, lineLength, StandardCharsets.UTF_8);
            int consumed = newline + 1;
            buffer.reset();
            if (consumed < bytes.length) {
                buffer.write(bytes, consumed, bytes.length - consumed);
            }
            processLine(line, events);
        }
        return events;
    }

    /**
     * Treat any unterminated trailing buffer as a final line, flush an
     * in-progress event, and return everything that hasn't yet been emitted.
     */
    public List<SseEvent> finish() {
        List<SseEvent> events = new ArrayList<>();
        if (buffer.size() > 0) {
            byte[] bytes = buffer.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == (byte) '\r') {
                length--;
            }
            String trailing = new String(bytes, 0, length, StandardCharsets.UTF_8);
            buffer.reset();
            processLine(trailing, events);
        }
        SseEvent pending = takeEvent();
        if (pending != null) {
            events.add(pending);
        }
        return events;
    }

    private void processLine(String line, List<SseEvent> events) {
        if (line.isEmpty()) {
            SseEvent event = takeEvent();
            if (event != null) {
                events.add(event);
            }
            return;
        }
        if (line.startsWith(":")) {
            return;
        }

        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            String rest = line.substring(colon + 1);
            if (!rest.isEmpty() && rest.charAt(0) == ' ') {
                rest = rest.substring(1);
            }
            value = rest;
        }

        switch (field) {
            case "event" -> eventName = value;
            case "data" -> dataLines.add(value);
            case "id" -> id = value;
            case "retry" -> {
                try {
                    retry = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // ignore non-numeric retry per the Rust impl
                }
            }
            default -> {
                // unknown fields are ignored, matching the SSE spec
            }
        }
    }

    private SseEvent takeEvent() {
        if (dataLines.isEmpty() && eventName == null && id == null && retry == null) {
            return null;
        }
        String data = String.join("\n", dataLines);
        dataLines.clear();
        SseEvent event = new SseEvent(eventName, data, id, retry);
        eventName = null;
        id = null;
        retry = null;
        return event;
    }
}
