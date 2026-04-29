package org.jclaude.api.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.StreamEvent;

/**
 * Stateful reader that converts a stream of UTF-8 bytes into SSE frames and
 * the corresponding {@link SseEvent}s.
 *
 * <p>This is the Java port of the Rust {@code SseParser} from the Anthropic
 * client. It splits the byte buffer on {@code \n\n} (or {@code \r\n\r\n})
 * exactly like the upstream parser, then for each frame extracts the
 * {@code event:}, {@code data:} and {@code id:} fields. {@code ping} events
 * and the literal {@code [DONE]} payload are dropped — they exist only for
 * keep-alive purposes and are not surfaced to callers.
 *
 * <p>The reader can be driven three ways:
 * <ol>
 *   <li>By feeding chunks via {@link #pushBytes(byte[])} as bytes arrive.</li>
 *   <li>By draining a blocking {@link InputStream} via {@link #readAll(InputStream)}.</li>
 *   <li>By subscribing it (as a {@link Flow.Subscriber}) to an upstream line publisher.</li>
 * </ol>
 *
 * <p>Use {@link #parseFrame(SseFrame)} or the static {@link #parseAnthropicFrame(String)}
 * to deserialise the {@code data} payload into a typed {@link StreamEvent}.
 */
public final class SseStreamReader {

    private static final byte[] DOUBLE_NEWLINE = {'\n', '\n'};
    private static final byte[] DOUBLE_CRLF = {'\r', '\n', '\r', '\n'};
    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final String provider;
    private final String model;

    public SseStreamReader() {
        this("unknown", "unknown");
    }

    public SseStreamReader(String provider, String model) {
        this.provider = Objects.requireNonNullElse(provider, "unknown");
        this.model = Objects.requireNonNullElse(model, "unknown");
    }

    /**
     * Append {@code chunk} to the internal buffer and return any complete
     * SSE events that have become available. {@code ping} and {@code [DONE]}
     * frames are filtered out.
     */
    public List<SseEvent> pushBytes(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            buffer.write(chunk, 0, chunk.length);
        }
        List<SseEvent> events = new ArrayList<>();
        SseFrame frame;
        while ((frame = nextFrame()) != null) {
            toSseEvent(frame).ifPresent(events::add);
        }
        return events;
    }

    /**
     * Flush any remaining buffered bytes as a final frame. Mirrors
     * {@code SseParser::finish} from the Rust client.
     */
    public List<SseEvent> finish() {
        if (buffer.size() == 0) {
            return List.of();
        }
        byte[] trailing = buffer.toByteArray();
        buffer.reset();
        String text = new String(trailing, StandardCharsets.UTF_8);
        return toSseEvent(new SseFrame(text)).map(List::of).orElse(List.of());
    }

    /**
     * Push a chunk of bytes and return the deserialised {@link StreamEvent}s
     * for any complete frames. Mirrors {@code SseParser::push} from the Rust
     * Anthropic client.
     */
    public List<StreamEvent> pushTypedBytes(byte[] chunk) {
        List<SseEvent> sseEvents = pushBytes(chunk);
        if (sseEvents.isEmpty()) {
            return List.of();
        }
        List<StreamEvent> typed = new ArrayList<>(sseEvents.size());
        for (SseEvent event : sseEvents) {
            parsePayload(event.data(), provider, model).ifPresent(typed::add);
        }
        return typed;
    }

    /**
     * Flush any trailing partial frame and deserialise it. Mirrors
     * {@code SseParser::finish} but returns typed events.
     */
    public List<StreamEvent> finishTyped() {
        List<SseEvent> sseEvents = finish();
        if (sseEvents.isEmpty()) {
            return List.of();
        }
        List<StreamEvent> typed = new ArrayList<>(sseEvents.size());
        for (SseEvent event : sseEvents) {
            parsePayload(event.data(), provider, model).ifPresent(typed::add);
        }
        return typed;
    }

    /**
     * Consume the entire {@code input}, returning all SSE events including
     * any trailing partial frame.
     */
    public List<SseEvent> readAll(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<SseEvent> events = new ArrayList<>();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            byte[] copy = new byte[read];
            System.arraycopy(chunk, 0, copy, 0, read);
            events.addAll(pushBytes(copy));
        }
        events.addAll(finish());
        return events;
    }

    /**
     * Subscribe this reader to a publisher of pre-split lines (e.g. the lines
     * produced by an HTTP body line stream). Each delivered string is treated
     * as a single line of the SSE stream — a trailing newline is appended
     * before it is fed into the buffer so the frame splitter sees the same
     * bytes as if they had come from a raw byte stream.
     */
    public Flow.Subscriber<String> asLineSubscriber(java.util.function.Consumer<SseEvent> sink) {
        Objects.requireNonNull(sink, "sink");
        return new LineSubscriber(this, sink);
    }

    /** Static helper that mirrors the Rust {@code parse_frame} free function. */
    public static Optional<StreamEvent> parseAnthropicFrame(String frame) {
        return parseAnthropicFrame(frame, "unknown", "unknown");
    }

    /** Static helper that mirrors the Rust {@code parse_frame_with_provider} function. */
    public static Optional<StreamEvent> parseAnthropicFrame(String frame, String provider, String model) {
        Optional<SseEvent> sse = toSseEvent(new SseFrame(frame));
        if (sse.isEmpty()) {
            return Optional.empty();
        }
        return parsePayload(sse.get().data(), provider, model);
    }

    /**
     * Deserialise the {@code data} payload of {@code frame} into a typed
     * {@link StreamEvent}. Returns empty when the frame contains no data,
     * holds only a ping event, or is the SSE-stream-terminating {@code [DONE]}
     * marker.
     */
    public Optional<StreamEvent> parseFrame(SseFrame frame) {
        Optional<SseEvent> sse = toSseEvent(frame);
        if (sse.isEmpty()) {
            return Optional.empty();
        }
        return parsePayload(sse.get().data(), provider, model);
    }

    private static Optional<StreamEvent> parsePayload(String payload, String provider, String model) {
        try {
            return Optional.ofNullable(MAPPER.readValue(payload, StreamEvent.class));
        } catch (JsonProcessingException error) {
            throw new SseParseException(provider, model, payload, error);
        }
    }

    /**
     * Drain the next complete frame from the buffer, or return {@code null}
     * if no terminator has been seen yet.
     */
    private SseFrame nextFrame() {
        byte[] bytes = buffer.toByteArray();
        int doubleLfIndex = indexOf(bytes, DOUBLE_NEWLINE);
        int doubleCrlfIndex = indexOf(bytes, DOUBLE_CRLF);

        int separatorPosition;
        int separatorLength;
        if (doubleLfIndex >= 0 && (doubleCrlfIndex < 0 || doubleLfIndex <= doubleCrlfIndex)) {
            separatorPosition = doubleLfIndex;
            separatorLength = DOUBLE_NEWLINE.length;
        } else if (doubleCrlfIndex >= 0) {
            separatorPosition = doubleCrlfIndex;
            separatorLength = DOUBLE_CRLF.length;
        } else {
            return null;
        }

        String frameText = new String(bytes, 0, separatorPosition, StandardCharsets.UTF_8);
        int totalConsumed = separatorPosition + separatorLength;
        // Replace the buffer with whatever remains after the consumed frame.
        buffer.reset();
        if (totalConsumed < bytes.length) {
            buffer.write(bytes, totalConsumed, bytes.length - totalConsumed);
        }
        return new SseFrame(frameText);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Convert a raw frame to an {@link SseEvent}, applying the same filtering
     * (ping events, {@code [DONE]} markers, data-less frames) as the Rust
     * parser.
     */
    static Optional<SseEvent> toSseEvent(SseFrame frame) {
        String trimmed = frame.text().trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String eventName = null;
        String id = null;
        List<String> dataLines = new ArrayList<>();

        for (String line : trimmed.split("\n", -1)) {
            // strip a single trailing CR so \r\n line endings are tolerated
            String normalised = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (normalised.isEmpty()) {
                continue;
            }
            if (normalised.startsWith(":")) {
                continue;
            }
            if (normalised.startsWith("event:")) {
                eventName = normalised.substring("event:".length()).trim();
                continue;
            }
            if (normalised.startsWith("data:")) {
                String value = normalised.substring("data:".length());
                // mirror Rust trim_start: drop a single leading space, but keep further whitespace
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
                continue;
            }
            if (normalised.startsWith("id:")) {
                String value = normalised.substring("id:".length());
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                id = value;
            }
            // unknown fields and retry: ignored at this level
        }

        if ("ping".equals(eventName)) {
            return Optional.empty();
        }
        if (dataLines.isEmpty()) {
            return Optional.empty();
        }
        String payload = String.join("\n", dataLines);
        if ("[DONE]".equals(payload)) {
            return Optional.empty();
        }
        return Optional.of(new SseEvent(eventName, payload, id));
    }

    private static final class LineSubscriber implements Flow.Subscriber<String> {

        private final SseStreamReader reader;
        private final java.util.function.Consumer<SseEvent> sink;
        private Flow.Subscription subscription;

        private LineSubscriber(SseStreamReader reader, java.util.function.Consumer<SseEvent> sink) {
            this.reader = reader;
            this.sink = sink;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            String withNewline = line.endsWith("\n") ? line : line + "\n";
            for (SseEvent event : reader.pushBytes(withNewline.getBytes(StandardCharsets.UTF_8))) {
                sink.accept(event);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // best-effort flush of any trailing partial frame, then propagate
            for (SseEvent event : reader.finish()) {
                sink.accept(event);
            }
        }

        @Override
        public void onComplete() {
            for (SseEvent event : reader.finish()) {
                sink.accept(event);
            }
        }
    }
}
