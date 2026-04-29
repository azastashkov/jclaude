package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StreamEvent.MessageStart.class, name = "message_start"),
    @JsonSubTypes.Type(value = StreamEvent.MessageDelta.class, name = "message_delta"),
    @JsonSubTypes.Type(value = StreamEvent.ContentBlockStart.class, name = "content_block_start"),
    @JsonSubTypes.Type(value = StreamEvent.ContentBlockDelta.class, name = "content_block_delta"),
    @JsonSubTypes.Type(value = StreamEvent.ContentBlockStop.class, name = "content_block_stop"),
    @JsonSubTypes.Type(value = StreamEvent.MessageStop.class, name = "message_stop"),
    @JsonSubTypes.Type(value = StreamEvent.Ping.class, name = "ping")
})
public sealed interface StreamEvent
        permits StreamEvent.MessageStart,
                StreamEvent.MessageDelta,
                StreamEvent.ContentBlockStart,
                StreamEvent.ContentBlockDelta,
                StreamEvent.ContentBlockStop,
                StreamEvent.MessageStop,
                StreamEvent.Ping {

    @JsonTypeName("message_start")
    record MessageStart(MessageResponse message) implements StreamEvent {}

    @JsonTypeName("message_delta")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessageDelta(Delta delta, Usage usage) implements StreamEvent {

        public record Delta(String stop_reason, String stop_sequence) {}
    }

    @JsonTypeName("content_block_start")
    record ContentBlockStart(int index, OutputContentBlock content_block) implements StreamEvent {}

    @JsonTypeName("content_block_delta")
    record ContentBlockDelta(int index, BlockDelta delta) implements StreamEvent {}

    @JsonTypeName("content_block_stop")
    record ContentBlockStop(int index) implements StreamEvent {}

    @JsonTypeName("message_stop")
    record MessageStop() implements StreamEvent {}

    @JsonTypeName("ping")
    record Ping() implements StreamEvent {}
}
