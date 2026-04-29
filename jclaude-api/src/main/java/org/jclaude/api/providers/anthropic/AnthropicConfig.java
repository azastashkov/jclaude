package org.jclaude.api.providers.anthropic;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for {@link AnthropicClient}.
 *
 * <p>Java port of the Rust {@code AnthropicClient} configuration surface in
 * {@code crates/api/src/providers/anthropic.rs}. The fields cover the full
 * matrix of authentication (api key, bearer token, or both), retry policy,
 * timeouts, and the {@code anthropic-beta} opt-in list shared with the
 * provider on every request.
 *
 * <p>Either {@link #api_key()} or {@link #auth_token()} (but typically only
 * one) must be present. When both are non-null, both headers are sent — the
 * Rust client supports this for proxy scenarios where the upstream needs an
 * api key and a separate bearer token (e.g. for Anthropic Vertex auth).
 */
public record AnthropicConfig(
        String base_url,
        String api_key,
        String auth_token,
        Duration timeout,
        int max_retries,
        Duration initial_backoff,
        Duration max_backoff,
        List<String> betas,
        String anthropic_version,
        String user_agent) {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_API_VERSION = "2023-06-01";
    public static final String DEFAULT_USER_AGENT = "jclaude/0.1.0";

    /** Default {@code anthropic-beta} value list, mirrors the Rust profile. */
    public static final String DEFAULT_AGENTIC_BETA = "claude-code-20250219";

    public static final String DEFAULT_PROMPT_CACHING_SCOPE_BETA = "prompt-caching-scope-2026-01-05";
    public static final List<String> DEFAULT_BETAS = List.of(DEFAULT_AGENTIC_BETA, DEFAULT_PROMPT_CACHING_SCOPE_BETA);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(128);
    public static final int DEFAULT_MAX_RETRIES = 8;

    public AnthropicConfig {
        Objects.requireNonNull(base_url, "base_url");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(initial_backoff, "initial_backoff");
        Objects.requireNonNull(max_backoff, "max_backoff");
        Objects.requireNonNull(betas, "betas");
        Objects.requireNonNull(anthropic_version, "anthropic_version");
        Objects.requireNonNull(user_agent, "user_agent");
        if (max_retries < 0) {
            throw new IllegalArgumentException("max_retries must be non-negative");
        }
        betas = List.copyOf(betas);
    }

    /** Build a config from an api key alone (most common path for tests/CLI). */
    public static AnthropicConfig of_api_key(String api_key) {
        return defaults().with_api_key(api_key);
    }

    /** Build a config from a bearer token alone (OAuth path). */
    public static AnthropicConfig of_auth_token(String auth_token) {
        return defaults().with_auth_token(auth_token);
    }

    /** Build a config with all fields at their default values. */
    public static AnthropicConfig defaults() {
        return new AnthropicConfig(
                DEFAULT_BASE_URL,
                null,
                null,
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_RETRIES,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF,
                DEFAULT_BETAS,
                DEFAULT_API_VERSION,
                DEFAULT_USER_AGENT);
    }

    public AnthropicConfig with_base_url(String new_base_url) {
        return new AnthropicConfig(
                new_base_url,
                api_key,
                auth_token,
                timeout,
                max_retries,
                initial_backoff,
                max_backoff,
                betas,
                anthropic_version,
                user_agent);
    }

    public AnthropicConfig with_api_key(String new_api_key) {
        return new AnthropicConfig(
                base_url,
                new_api_key,
                auth_token,
                timeout,
                max_retries,
                initial_backoff,
                max_backoff,
                betas,
                anthropic_version,
                user_agent);
    }

    public AnthropicConfig with_auth_token(String new_auth_token) {
        return new AnthropicConfig(
                base_url,
                api_key,
                new_auth_token,
                timeout,
                max_retries,
                initial_backoff,
                max_backoff,
                betas,
                anthropic_version,
                user_agent);
    }

    public AnthropicConfig with_retry_policy(int new_max_retries, Duration new_initial, Duration new_max) {
        return new AnthropicConfig(
                base_url,
                api_key,
                auth_token,
                timeout,
                new_max_retries,
                new_initial,
                new_max,
                betas,
                anthropic_version,
                user_agent);
    }

    public AnthropicConfig with_betas(List<String> new_betas) {
        return new AnthropicConfig(
                base_url,
                api_key,
                auth_token,
                timeout,
                max_retries,
                initial_backoff,
                max_backoff,
                new_betas,
                anthropic_version,
                user_agent);
    }

    public AnthropicConfig with_user_agent(String new_user_agent) {
        return new AnthropicConfig(
                base_url,
                api_key,
                auth_token,
                timeout,
                max_retries,
                initial_backoff,
                max_backoff,
                betas,
                anthropic_version,
                new_user_agent);
    }
}
