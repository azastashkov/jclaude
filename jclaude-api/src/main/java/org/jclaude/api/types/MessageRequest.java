package org.jclaude.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageRequest(
        String model,
        long max_tokens,
        List<InputMessage> messages,
        String system,
        List<ToolDefinition> tools,
        ToolChoice tool_choice,
        boolean stream,
        Double temperature,
        Double top_p,
        Double frequency_penalty,
        Double presence_penalty,
        List<String> stop,
        String reasoning_effort) {

    public static Builder builder() {
        return new Builder();
    }

    public MessageRequest with_streaming() {
        return new MessageRequest(
                model,
                max_tokens,
                messages,
                system,
                tools,
                tool_choice,
                true,
                temperature,
                top_p,
                frequency_penalty,
                presence_penalty,
                stop,
                reasoning_effort);
    }

    public static final class Builder {
        private String model;
        private long max_tokens;
        private List<InputMessage> messages = List.of();
        private String system;
        private List<ToolDefinition> tools;
        private ToolChoice tool_choice;
        private boolean stream;
        private Double temperature;
        private Double top_p;
        private Double frequency_penalty;
        private Double presence_penalty;
        private List<String> stop;
        private String reasoning_effort;

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder max_tokens(long v) {
            this.max_tokens = v;
            return this;
        }

        public Builder messages(List<InputMessage> v) {
            this.messages = v;
            return this;
        }

        public Builder system(String v) {
            this.system = v;
            return this;
        }

        public Builder tools(List<ToolDefinition> v) {
            this.tools = v;
            return this;
        }

        public Builder tool_choice(ToolChoice v) {
            this.tool_choice = v;
            return this;
        }

        public Builder stream(boolean v) {
            this.stream = v;
            return this;
        }

        public Builder temperature(Double v) {
            this.temperature = v;
            return this;
        }

        public Builder top_p(Double v) {
            this.top_p = v;
            return this;
        }

        public Builder frequency_penalty(Double v) {
            this.frequency_penalty = v;
            return this;
        }

        public Builder presence_penalty(Double v) {
            this.presence_penalty = v;
            return this;
        }

        public Builder stop(List<String> v) {
            this.stop = v;
            return this;
        }

        public Builder reasoning_effort(String v) {
            this.reasoning_effort = v;
            return this;
        }

        public MessageRequest build() {
            return new MessageRequest(
                    model,
                    max_tokens,
                    messages,
                    system,
                    tools,
                    tool_choice,
                    stream,
                    temperature,
                    top_p,
                    frequency_penalty,
                    presence_penalty,
                    stop,
                    reasoning_effort);
        }
    }
}
