package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BlockDelta.TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = BlockDelta.InputJsonDelta.class, name = "input_json_delta"),
    @JsonSubTypes.Type(value = BlockDelta.ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = BlockDelta.SignatureDelta.class, name = "signature_delta")
})
public sealed interface BlockDelta
        permits BlockDelta.TextDelta, BlockDelta.InputJsonDelta, BlockDelta.ThinkingDelta, BlockDelta.SignatureDelta {

    @JsonTypeName("text_delta")
    record TextDelta(String text) implements BlockDelta {}

    @JsonTypeName("input_json_delta")
    record InputJsonDelta(String partial_json) implements BlockDelta {}

    @JsonTypeName("thinking_delta")
    record ThinkingDelta(String thinking) implements BlockDelta {}

    @JsonTypeName("signature_delta")
    record SignatureDelta(String signature) implements BlockDelta {}
}
