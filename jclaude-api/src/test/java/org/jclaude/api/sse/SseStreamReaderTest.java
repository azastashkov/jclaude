package org.jclaude.api.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.Usage;
import org.junit.jupiter.api.Test;

class SseStreamReaderTest {

    @Test
    void parses_single_frame() {
        String frame = "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Hi\"}}\n\n";

        Optional<StreamEvent> event = SseStreamReader.parseAnthropicFrame(frame);

        assertThat(event).contains(new StreamEvent.ContentBlockStart(0, new OutputContentBlock.Text("Hi")));
    }

    @Test
    void parses_chunked_stream() {
        SseStreamReader parser = new SseStreamReader();
        byte[] first = ("event: content_block_delta\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel")
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "lo\"}}\n\n".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.pushTypedBytes(first)).isEmpty();
        List<StreamEvent> events = parser.pushTypedBytes(second);

        assertThat(events).containsExactly(new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("Hello")));
    }

    @Test
    void ignores_ping_and_done() {
        SseStreamReader parser = new SseStreamReader();
        String payload = ": keepalive\n"
                + "event: ping\n"
                + "data: {\"type\":\"ping\"}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n"
                + "data: [DONE]\n\n";

        List<StreamEvent> events = parser.pushTypedBytes(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(events)
                .containsExactly(
                        new StreamEvent.MessageDelta(
                                new StreamEvent.MessageDelta.Delta("tool_use", null), new Usage(1L, 0L, 0L, 2L)),
                        new StreamEvent.MessageStop());
    }

    @Test
    void ignores_data_less_event_frames() {
        String frame = "event: ping\n\n";

        Optional<StreamEvent> event = SseStreamReader.parseAnthropicFrame(frame);

        assertThat(event).isEmpty();
    }

    @Test
    void parses_split_json_across_data_lines() {
        String frame = "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\n"
                + "data: \"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n";

        Optional<StreamEvent> event = SseStreamReader.parseAnthropicFrame(frame);

        assertThat(event).contains(new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta("Hello")));
    }

    @Test
    void parses_thinking_content_block_start() {
        String frame = "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":null}}\n\n";

        Optional<StreamEvent> event = SseStreamReader.parseAnthropicFrame(frame);

        assertThat(event).contains(new StreamEvent.ContentBlockStart(0, new OutputContentBlock.Thinking("", null)));
    }

    @Test
    void parses_thinking_related_deltas() {
        String thinking = "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"step 1\"}}\n\n";
        String signature = "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_123\"}}\n\n";

        Optional<StreamEvent> thinkingEvent = SseStreamReader.parseAnthropicFrame(thinking);
        Optional<StreamEvent> signatureEvent = SseStreamReader.parseAnthropicFrame(signature);

        assertThat(thinkingEvent)
                .contains(new StreamEvent.ContentBlockDelta(0, new BlockDelta.ThinkingDelta("step 1")));
        assertThat(signatureEvent)
                .contains(new StreamEvent.ContentBlockDelta(0, new BlockDelta.SignatureDelta("sig_123")));
    }

    @Test
    void given_message_delta_frame_with_empty_usage_when_parsed_then_usage_defaults_to_zero() {
        // given
        String frame = "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{}}\n\n";

        // when
        Optional<StreamEvent> event = SseStreamReader.parseAnthropicFrame(frame);

        // then
        assertThat(event)
                .contains(
                        new StreamEvent.MessageDelta(new StreamEvent.MessageDelta.Delta("end_turn", null), Usage.ZERO));
    }
}
