package org.jclaude.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TelemetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void request_profile_emits_headers_and_merges_body() throws Exception {
        AnthropicRequestProfile profile = AnthropicRequestProfile.of(
                        ClientIdentity.of("claude-code", "1.2.3").with_runtime("java-cli"))
                .with_beta("tools-2026-04-01")
                .with_extra_body("metadata", MAPPER.readTree("{\"source\":\"test\"}"));

        assertThat(profile.header_pairs())
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "anthropic-version", AnthropicRequestProfile.DEFAULT_ANTHROPIC_VERSION),
                        org.assertj.core.groups.Tuple.tuple("user-agent", "claude-code/1.2.3"),
                        org.assertj.core.groups.Tuple.tuple(
                                "anthropic-beta",
                                "claude-code-20250219,prompt-caching-scope-2026-01-05,tools-2026-04-01"));

        var body = profile.render_json_body(MAPPER.readTree("{\"model\":\"claude-sonnet\"}"));
        assertThat(body.path("metadata").path("source").asText()).isEqualTo("test");
        assertThat(body.path("betas").isArray()).isTrue();
        assertThat(body.path("betas").size()).isEqualTo(3);
        assertThat(body.path("betas").path(0).asText()).isEqualTo("claude-code-20250219");
        assertThat(body.path("betas").path(2).asText()).isEqualTo("tools-2026-04-01");
    }

    @Test
    void session_tracer_records_structured_events_and_trace_sequence() {
        MemoryTelemetrySink sink = new MemoryTelemetrySink();
        SessionTracer tracer = new SessionTracer("session-123", sink);

        tracer.record_http_request_started(1, "POST", "/v1/messages", new LinkedHashMap<>());
        tracer.record_analytics(
                AnalyticsEvent.of("cli", "prompt_sent").with_property("model", TextNode.valueOf("claude-opus")));

        var events = sink.events();
        assertThat(events.get(0)).isInstanceOf(TelemetryEvent.HttpRequestStarted.class);
        var started = (TelemetryEvent.HttpRequestStarted) events.get(0);
        assertThat(started.session_id()).isEqualTo("session-123");
        assertThat(started.attempt()).isEqualTo(1);
        assertThat(started.method()).isEqualTo("POST");
        assertThat(started.path()).isEqualTo("/v1/messages");

        assertThat(events.get(1)).isInstanceOf(TelemetryEvent.SessionTrace.class);
        var trace1 = ((TelemetryEvent.SessionTrace) events.get(1)).record();
        assertThat(trace1.sequence()).isEqualTo(0L);
        assertThat(trace1.name()).isEqualTo("http_request_started");

        assertThat(events.get(2)).isInstanceOf(TelemetryEvent.Analytics.class);
        assertThat(events.get(3)).isInstanceOf(TelemetryEvent.SessionTrace.class);
        var trace2 = ((TelemetryEvent.SessionTrace) events.get(3)).record();
        assertThat(trace2.sequence()).isEqualTo(1L);
        assertThat(trace2.name()).isEqualTo("analytics");
    }

    @Test
    void jsonl_sink_persists_events() throws Exception {
        Path path = Files.createTempFile("telemetry-jsonl-", ".log");
        try (JsonlTelemetrySink sink = new JsonlTelemetrySink(path)) {
            sink.record(new TelemetryEvent.Analytics(AnalyticsEvent.of("cli", "turn_completed")
                    .with_property("ok", com.fasterxml.jackson.databind.node.BooleanNode.TRUE)));
        }
        String contents = Files.readString(path);
        assertThat(contents).contains("\"type\":\"analytics\"");
        assertThat(contents).contains("\"action\":\"turn_completed\"");
        Files.deleteIfExists(path);
    }

    @Test
    void session_tracer_records_succeeded_and_failed_http_calls() {
        MemoryTelemetrySink sink = new MemoryTelemetrySink();
        SessionTracer tracer = new SessionTracer("sess", sink);
        tracer.record_http_request_succeeded(
                2, "POST", "/v1/messages", 200, Optional.of("req_abc"), new LinkedHashMap<>());
        tracer.record_http_request_failed(3, "POST", "/v1/messages", "boom", true, new LinkedHashMap<>());

        var events = sink.events();
        assertThat(events.get(0)).isInstanceOf(TelemetryEvent.HttpRequestSucceeded.class);
        var succeeded = (TelemetryEvent.HttpRequestSucceeded) events.get(0);
        assertThat(succeeded.status()).isEqualTo(200);
        assertThat(succeeded.request_id()).contains("req_abc");

        assertThat(events.get(2)).isInstanceOf(TelemetryEvent.HttpRequestFailed.class);
        var failed = (TelemetryEvent.HttpRequestFailed) events.get(2);
        assertThat(failed.error()).isEqualTo("boom");
        assertThat(failed.retryable()).isTrue();
    }

    @SuppressWarnings("unused")
    private static IntNode i(int v) {
        return IntNode.valueOf(v);
    }
}
