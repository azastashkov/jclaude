package org.jclaude.api.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jclaude.api.providers.anthropic.AnthropicConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Java port of {@code rust/crates/api/tests/provider_client_integration.rs}.
 *
 * <p>Java cannot mutate {@link System#getenv()} after JVM start, so we use the
 * {@code Providers.ENV_OVERRIDES_FOR_TESTING} hook (the same mechanism used by
 * {@link OpenAiCompatIntegrationTest#provider_client_dispatches_xai_requests_from_env})
 * to simulate process env-var changes.
 */
class ProviderClientIntegrationTest {

    @AfterEach
    void clear_env_overrides() {
        Providers.ENV_OVERRIDES_FOR_TESTING.clear();
    }

    @Test
    void provider_client_routes_grok_aliases_through_xai() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "xai-test-key");

        // detect_provider_kind / metadata_for_model are the Java surface for what
        // the Rust ProviderClient::from_model returns: a kind plus the env config.
        assertThat(Providers.detect_provider_kind("grok-mini")).isEqualTo(ProviderKind.XAI);
        assertThat(Providers.resolve_model_alias("grok-mini")).isEqualTo("grok-3-mini");
        ProviderMetadata metadata = Providers.metadata_for_model("grok-mini").orElseThrow();
        assertThat(metadata.provider()).isEqualTo(ProviderKind.XAI);
        assertThat(metadata.auth_env()).isEqualTo("XAI_API_KEY");
    }

    @Test
    void provider_client_reports_missing_xai_credentials_for_grok_models() {
        // Explicitly clear XAI_API_KEY (the override map already empty after each test).
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");

        // The Java port surfaces missing credentials at client-construction time
        // via OpenAiCompatClient.from_env, mirroring ProviderClient::from_model.
        assertThatThrownBy(() -> OpenAiCompatClient.from_env(OpenAiCompatConfig.xai()))
                .isInstanceOf(OpenAiCompatException.class)
                .satisfies(error -> {
                    OpenAiCompatException ex = (OpenAiCompatException) error;
                    assertThat(ex.kind()).isEqualTo(OpenAiCompatException.Kind.MISSING_CREDENTIALS);
                    assertThat(ex.getMessage()).contains("xAI");
                    assertThat(ex.getMessage()).contains("XAI_API_KEY");
                });
    }

    @Test
    void provider_client_uses_explicit_anthropic_auth_without_env_lookup() {
        // Even without any ANTHROPIC_API_KEY/ANTHROPIC_AUTH_TOKEN in env, an
        // explicit AnthropicConfig.of_api_key should construct a client without
        // touching the environment.
        Providers.ENV_OVERRIDES_FOR_TESTING.put("ANTHROPIC_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("ANTHROPIC_AUTH_TOKEN", "");

        AnthropicConfig config = AnthropicConfig.of_api_key("anthropic-test-key");
        assertThat(config.api_key()).isEqualTo("anthropic-test-key");
        assertThat(Providers.detect_provider_kind("claude-sonnet-4-6")).isEqualTo(ProviderKind.ANTHROPIC);
    }

    @Test
    void read_xai_base_url_prefers_env_override() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_BASE_URL", "https://example.xai.test/v1");

        assertThat(OpenAiCompatClient.read_base_url(OpenAiCompatConfig.xai())).isEqualTo("https://example.xai.test/v1");
    }
}
