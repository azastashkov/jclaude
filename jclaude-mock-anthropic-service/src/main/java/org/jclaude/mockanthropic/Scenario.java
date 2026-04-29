package org.jclaude.mockanthropic;

import java.util.Optional;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;

/**
 * The 12 deterministic mock-parity scenarios. The mock service selects one of these by scanning user
 * messages for a token of the form {@code PARITY_SCENARIO:<name>} and dispatches a fixed response
 * sequence to give byte-equivalent parity with the upstream Rust mock-anthropic-service.
 */
public enum Scenario {
    STREAMING_TEXT("streaming_text"),
    READ_FILE_ROUNDTRIP("read_file_roundtrip"),
    GREP_CHUNK_ASSEMBLY("grep_chunk_assembly"),
    WRITE_FILE_ALLOWED("write_file_allowed"),
    WRITE_FILE_DENIED("write_file_denied"),
    MULTI_TOOL_TURN_ROUNDTRIP("multi_tool_turn_roundtrip"),
    BASH_STDOUT_ROUNDTRIP("bash_stdout_roundtrip"),
    BASH_PERMISSION_PROMPT_APPROVED("bash_permission_prompt_approved"),
    BASH_PERMISSION_PROMPT_DENIED("bash_permission_prompt_denied"),
    PLUGIN_TOOL_ROUNDTRIP("plugin_tool_roundtrip"),
    AUTO_COMPACT_TRIGGERED("auto_compact_triggered"),
    TOKEN_COST_REPORTING("token_cost_reporting");

    public static final String SCENARIO_PREFIX = "PARITY_SCENARIO:";

    private final String wire_name;

    Scenario(String wire_name) {
        this.wire_name = wire_name;
    }

    public String wire_name() {
        return wire_name;
    }

    /** Resolve a scenario name string (the value after the prefix) to its enum constant. */
    public static Optional<Scenario> from_name(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        for (Scenario s : values()) {
            if (s.wire_name.equals(trimmed)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Extract the scenario, if any, from a single user-text fragment. */
    public static Optional<Scenario> from_user_text(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (String token : text.split("\\s+")) {
            if (token.startsWith(SCENARIO_PREFIX)) {
                Optional<Scenario> resolved = from_name(token.substring(SCENARIO_PREFIX.length()));
                if (resolved.isPresent()) {
                    return resolved;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Walk the request's messages from latest to earliest, looking inside text blocks for the most
     * recent scenario marker. This mirrors the Rust {@code detect_scenario} helper.
     */
    public static Optional<Scenario> detect(MessageRequest request) {
        if (request == null || request.messages() == null) {
            return Optional.empty();
        }
        var messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            InputMessage message = messages.get(i);
            if (message == null || message.content() == null) {
                continue;
            }
            var content = message.content();
            for (int j = content.size() - 1; j >= 0; j--) {
                InputContentBlock block = content.get(j);
                if (block instanceof InputContentBlock.Text text) {
                    Optional<Scenario> resolved = from_user_text(text.text());
                    if (resolved.isPresent()) {
                        return resolved;
                    }
                }
            }
        }
        return Optional.empty();
    }
}
