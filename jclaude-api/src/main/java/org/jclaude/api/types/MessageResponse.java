package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(
        String id,
        @JsonProperty("type") String kind,
        String role,
        List<OutputContentBlock> content,
        String model,
        String stop_reason,
        String stop_sequence,
        Usage usage,
        String request_id) {

    public long total_tokens() {
        return usage == null ? 0 : usage.total_tokens();
    }
}
