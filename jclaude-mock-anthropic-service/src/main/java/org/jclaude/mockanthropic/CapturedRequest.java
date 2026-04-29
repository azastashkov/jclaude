package org.jclaude.mockanthropic;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A snapshot of an HTTP request received by the mock service. Used by parity tests to inspect what
 * the client actually sent on the wire.
 */
public record CapturedRequest(
        String path,
        String method,
        JsonNode body,
        boolean stream,
        Map<String, String> headers,
        Optional<Scenario> scenario,
        Instant timestamp) {}
