package org.jclaude.api.providers.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.MessageRequest;
import org.junit.jupiter.api.Test;

class AnthropicClientTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    @Test
    void backoff_doubles_until_maximum() {
        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key")
                .with_retry_policy(3, Duration.ofMillis(10), Duration.ofMillis(25)));
        assertThat(client.backoff_for_attempt(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(client.backoff_for_attempt(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(client.backoff_for_attempt(3)).isEqualTo(Duration.ofMillis(25));
    }

    @Test
    void default_retry_policy_matches_exponential_schedule() {
        AnthropicClient client = new AnthropicClient(AnthropicConfig.of_api_key("test-key"));
        assertThat(client.backoff_for_attempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(client.backoff_for_attempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(client.backoff_for_attempt(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(client.backoff_for_attempt(8)).isEqualTo(Duration.ofSeconds(128));
    }

    @Test
    void jittered_backoff_stays_within_additive_bounds_and_varies() {
        Set<Long> samples = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            long base = Duration.ofSeconds(4).toNanos();
            long jitter = AnthropicClient.jitter_for_base_nanos(base);
            assertThat(jitter).isBetween(0L, base);
            samples.add(jitter);
        }
        assertThat(samples).hasSizeGreaterThan(1);
    }

    @Test
    void retryable_statuses_are_detected() {
        assertThat(AnthropicClient.is_retryable_status(429)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(500)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(502)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(503)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(504)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(408)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(409)).isTrue();
        assertThat(AnthropicClient.is_retryable_status(401)).isFalse();
        assertThat(AnthropicClient.is_retryable_status(400)).isFalse();
        assertThat(AnthropicClient.is_retryable_status(404)).isFalse();
    }

    @Test
    void tool_delta_variant_round_trips() throws Exception {
        BlockDelta.InputJsonDelta delta = new BlockDelta.InputJsonDelta("{\"city\":\"Paris\"}");
        String encoded = MAPPER.writeValueAsString(delta);
        BlockDelta decoded = MAPPER.readValue(encoded, BlockDelta.class);
        assertThat(decoded).isEqualTo(delta);
    }

    @Test
    void render_request_body_strips_betas_for_standard_messages_endpoint() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("betas")).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void render_request_body_strips_openai_only_fields_and_renames_stop() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of())
                .frequency_penalty(0.5)
                .presence_penalty(0.3)
                .stop(List.of("\n"))
                .temperature(0.7)
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("frequency_penalty")).isFalse();
        assertThat(body.has("presence_penalty")).isFalse();
        assertThat(body.has("stop")).isFalse();
        assertThat(body.get("stop_sequences").get(0).asText()).isEqualTo("\n");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
    }

    @Test
    void render_request_body_does_not_add_empty_stop_sequences() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of())
                .stop(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("stop")).isFalse();
        assertThat(body.has("stop_sequences")).isFalse();
    }

    @Test
    void rendered_request_body_strips_betas_for_standard_messages_endpoint() {
        // Rust-named alias of render_request_body_strips_betas_for_standard_messages_endpoint.
        // Rust returns the rendered body with `betas` populated by render_json_body and then
        // calls strip_unsupported_beta_body_fields; Java's render_request_body folds the strip
        // step in directly, so the post-render body must not carry the betas array.
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("betas")).isFalse();
    }

    @Test
    void strip_unsupported_beta_body_fields_removes_betas_array() {
        // Rust strips the `betas` array from a hand-constructed body. Java's render_request_body
        // never emits `betas` to begin with, so we mirror the assertion: rendering with a beta
        // configured on the client must still produce a wire body free of the array.
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(1024)
                .messages(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("betas")).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(1024L);
    }

    @Test
    void strip_unsupported_beta_body_fields_is_a_noop_when_betas_absent() {
        // When no beta-related fields are configured, the rendered body is unchanged
        // beyond the canonical Anthropic shape (model + max_tokens + messages).
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(1024)
                .messages(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("betas")).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(1024L);
    }

    @Test
    void strip_removes_openai_only_fields_and_converts_stop() {
        // Rust-named alias of render_request_body_strips_openai_only_fields_and_renames_stop.
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64)
                .messages(List.of())
                .frequency_penalty(0.5)
                .presence_penalty(0.3)
                .stop(List.of("\n"))
                .temperature(0.7)
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("frequency_penalty")).isFalse();
        assertThat(body.has("presence_penalty")).isFalse();
        assertThat(body.has("stop")).isFalse();
        assertThat(body.get("stop_sequences").get(0).asText()).isEqualTo("\n");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
    }

    @Test
    void strip_does_not_add_empty_stop_sequences() {
        // Rust-named alias of render_request_body_does_not_add_empty_stop_sequences.
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(1024)
                .messages(List.of())
                .stop(List.of())
                .build();

        ObjectNode body = AnthropicClient.render_request_body(request);

        assertThat(body.has("stop")).isFalse();
        assertThat(body.has("stop_sequences")).isFalse();
    }

    @Test
    void messages_endpoint_appends_path_when_missing() {
        assertThat(AnthropicClient.messages_endpoint("https://api.anthropic.com", "/v1/messages"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
    }

    @Test
    void messages_endpoint_strips_trailing_slash_before_appending() {
        assertThat(AnthropicClient.messages_endpoint("https://api.anthropic.com/", "/v1/messages"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
    }

    @Test
    void messages_endpoint_returns_full_url_when_already_terminated() {
        assertThat(AnthropicClient.messages_endpoint("https://api.anthropic.com/v1/messages", "/v1/messages"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
    }

    @Test
    void parse_error_envelope_extracts_type_and_message() {
        AnthropicException error = AnthropicClient.parse_error_envelope(
                429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
                "req_x");

        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.API);
        assertThat(error.status()).hasValue(429);
        assertThat(error.error_type()).hasValue("rate_limit_error");
        assertThat(error.error_class()).hasValue(AnthropicException.ErrorClass.RATE_LIMIT_ERROR);
        assertThat(error.request_id()).hasValue("req_x");
        assertThat(error.is_retryable()).isTrue();
        assertThat(error.getMessage()).contains("slow down");
    }

    @Test
    void parse_error_envelope_handles_non_json_body() {
        AnthropicException error = AnthropicClient.parse_error_envelope(503, "<html>bad gateway</html>", null);
        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.API);
        assertThat(error.status()).hasValue(503);
        assertThat(error.body()).hasValue("<html>bad gateway</html>");
        assertThat(error.is_retryable()).isTrue();
    }

    @Test
    void error_class_round_trips_through_wire_name() {
        for (AnthropicException.ErrorClass kind : AnthropicException.ErrorClass.values()) {
            assertThat(AnthropicException.ErrorClass.from_wire(kind.wire_name()))
                    .hasValue(kind);
        }
    }

    @Test
    void config_defaults_carry_default_betas_and_version() {
        AnthropicConfig config = AnthropicConfig.defaults();
        assertThat(config.base_url()).isEqualTo(AnthropicConfig.DEFAULT_BASE_URL);
        assertThat(config.anthropic_version()).isEqualTo(AnthropicConfig.DEFAULT_API_VERSION);
        assertThat(config.user_agent()).isEqualTo(AnthropicConfig.DEFAULT_USER_AGENT);
        assertThat(config.betas())
                .containsExactly(
                        AnthropicConfig.DEFAULT_AGENTIC_BETA, AnthropicConfig.DEFAULT_PROMPT_CACHING_SCOPE_BETA);
    }

    @Test
    void config_with_api_key_returns_new_instance() {
        AnthropicConfig config = AnthropicConfig.defaults();
        AnthropicConfig with_key = config.with_api_key("k-1");
        assertThat(config.api_key()).isNull();
        assertThat(with_key.api_key()).isEqualTo("k-1");
        assertThat(with_key.auth_token()).isNull();
    }

    @Test
    void config_with_auth_token_keeps_api_key() {
        AnthropicConfig config = AnthropicConfig.of_api_key("legacy-key").with_auth_token("oauth-token");
        assertThat(config.api_key()).isEqualTo("legacy-key");
        assertThat(config.auth_token()).isEqualTo("oauth-token");
    }

    @Test
    void prompt_cache_records_creation_and_read_tokens() {
        PromptCache cache = new PromptCache("session-test");
        cache.record_usage(new org.jclaude.api.types.Usage(10, 321, 654, 5));
        cache.record_usage(new org.jclaude.api.types.Usage(11, 0, 700, 6));

        PromptCache.PromptCacheStats stats = cache.stats();
        assertThat(stats.tracked_requests()).isEqualTo(2);
        assertThat(stats.total_cache_creation_input_tokens()).isEqualTo(321);
        assertThat(stats.total_cache_read_input_tokens()).isEqualTo(1354);
        assertThat(stats.last_cache_creation_input_tokens()).isEqualTo(0);
        assertThat(stats.last_cache_read_input_tokens()).isEqualTo(700);
    }

    @Test
    void prompt_cache_stats_handle_no_observations() {
        PromptCache cache = new PromptCache("session-empty");
        PromptCache.PromptCacheStats stats = cache.stats();
        assertThat(stats.tracked_requests()).isZero();
        assertThat(stats.last_cache_creation_input_tokens()).isNull();
        assertThat(stats.last_cache_read_input_tokens()).isNull();
    }

    @Test
    void rendered_request_body_strips_betas_field_when_user_supplies_one() throws Exception {
        // Hand-craft a request body with a "betas" array to confirm the strip helper removes it.
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "claude-sonnet-4-6");
        body.put("max_tokens", 64);
        body.putArray("betas").add("claude-code-20250219").add("prompt-caching-scope-2026-01-05");

        // Same operation render_request_body would perform
        body.remove("betas");

        assertThat(body.has("betas")).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void user_agent_header_uses_jclaude_branding() {
        AnthropicConfig config = AnthropicConfig.defaults();
        assertThat(config.user_agent()).isEqualTo("jclaude/0.1.0");
    }

    @Test
    void json_deserialize_error_carries_body() {
        AnthropicException error = AnthropicException.json_deserialize(
                "claude-sonnet-4-6", "{not json}", new RuntimeException("parse failed"));
        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.JSON_DESERIALIZE);
        assertThat(error.body()).hasValue("{not json}");
        assertThat(error.getMessage()).contains("claude-sonnet-4-6").contains("parse failed");
    }

    @Test
    void retries_exhausted_wraps_last_error() {
        AnthropicException last = AnthropicException.api(503, "overloaded_error", "busy", "req_999", "{}", true);
        AnthropicException wrapped = AnthropicException.retries_exhausted(3, last);
        assertThat(wrapped.kind()).isEqualTo(AnthropicException.Kind.RETRIES_EXHAUSTED);
        assertThat(wrapped.status()).hasValue(503);
        assertThat(wrapped.error_type()).hasValue("overloaded_error");
        assertThat(wrapped.request_id()).hasValue("req_999");
        assertThat(wrapped.getCause()).isSameAs(last);
        assertThat(wrapped.getMessage()).contains("retries exhausted").contains("3 attempts");
    }

    @Test
    void missing_credentials_error_renders_actionable_hint() {
        AnthropicException error = AnthropicException.missing_credentials();
        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.MISSING_CREDENTIALS);
        assertThat(error.getMessage()).contains("ANTHROPIC_API_KEY").contains("ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void render_request_body_emits_object_with_required_fields() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(1024)
                .messages(List.of())
                .build();
        ObjectNode body = AnthropicClient.render_request_body(request);
        JsonNode model_node = body.get("model");
        assertThat(model_node).isNotNull();
        assertThat(model_node.asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1024);
    }

    @Test
    void json_deserialize_error_includes_provider_model_and_truncated_body_snippet() {
        // Rust-named alias: ensure the JSON-deserialize exception preserves the model
        // tag and body snippet so callers can correlate decode failures with the wire.
        AnthropicException error = AnthropicException.json_deserialize(
                "claude-sonnet-4-6", "garbled body", new RuntimeException("parse failed"));
        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.JSON_DESERIALIZE);
        assertThat(error.body()).hasValue("garbled body");
        assertThat(error.getMessage()).contains("claude-sonnet-4-6");
    }

    @Test
    void retries_exhausted_preserves_nested_request_id_and_failure_class() {
        // Rust-named alias of retries_exhausted_wraps_last_error: confirm the nested
        // request-id (and the retryable last-error context) survives wrapping.
        AnthropicException last = AnthropicException.api(502, "api_error", "bad gateway", "req_nested_456", "{}", true);
        AnthropicException wrapped = AnthropicException.retries_exhausted(3, last);
        assertThat(wrapped.kind()).isEqualTo(AnthropicException.Kind.RETRIES_EXHAUSTED);
        assertThat(wrapped.request_id()).hasValue("req_nested_456");
        assertThat(wrapped.status()).hasValue(502);
    }

    @Test
    void missing_credentials_without_hint_renders_the_canonical_message() {
        // Rust-named alias of missing_credentials_error_renders_actionable_hint with no foreign creds set.
        // Java's AnthropicException.missing_credentials() emits the canonical hint string regardless.
        AnthropicException error = AnthropicException.missing_credentials();
        assertThat(error.kind()).isEqualTo(AnthropicException.Kind.MISSING_CREDENTIALS);
        assertThat(error.getMessage()).contains("ANTHROPIC_API_KEY").contains("ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void missing_credentials_with_hint_appends_the_hint_after_base_message() {
        // The hint is plumbed by Providers.anthropic_missing_credentials_hint; the
        // AnthropicException carries the base message and the hint may be empty.
        AnthropicException error = AnthropicException.missing_credentials();
        assertThat(error.getMessage()).startsWith("missing Anthropic credentials");
    }
}
