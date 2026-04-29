package org.jclaude.runtime.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalSseParserTest {

    @Test
    void parses_streaming_events() {
        // given
        IncrementalSseParser parser = new IncrementalSseParser();

        // when
        List<SseEvent> first = parser.pushChunk("event: message\ndata: hel");

        // then
        assertThat(first).isEmpty();

        List<SseEvent> second = parser.pushChunk("lo\n\nid: 1\ndata: world\n\n");
        assertThat(second)
                .containsExactly(new SseEvent("message", "hello", null, null), new SseEvent(null, "world", "1", null));
    }

    @Test
    void finish_flushes_a_trailing_event_without_separator() {
        // given
        IncrementalSseParser parser = new IncrementalSseParser();
        parser.pushChunk("event: message\ndata: trailing");

        // when
        List<SseEvent> events = parser.finish();

        // then
        assertThat(events).containsExactly(new SseEvent("message", "trailing", null, null));
    }
}
