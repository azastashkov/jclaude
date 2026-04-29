package org.jclaude.api.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.ToolChoice;
import org.jclaude.api.types.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvidersTest {

    @AfterEach
    void clear_env_overrides() {
        Providers.ENV_OVERRIDES_FOR_TESTING.clear();
    }

    @Test
    void resolves_grok_aliases() {
        assertThat(Providers.resolve_model_alias("grok")).isEqualTo("grok-3");
        assertThat(Providers.resolve_model_alias("grok-mini")).isEqualTo("grok-3-mini");
        assertThat(Providers.resolve_model_alias("grok-2")).isEqualTo("grok-2");
    }

    @Test
    void resolves_existing_and_grok_aliases() {
        assertThat(Providers.resolve_model_alias("opus")).isEqualTo("claude-opus-4-6");
        assertThat(Providers.resolve_model_alias("grok")).isEqualTo("grok-3");
        assertThat(Providers.resolve_model_alias("grok-mini")).isEqualTo("grok-3-mini");
    }

    @Test
    void provider_detection_prefers_model_family() {
        assertThat(Providers.detect_provider_kind("grok-3")).isEqualTo(ProviderKind.XAI);
        assertThat(Providers.detect_provider_kind("claude-sonnet-4-6")).isEqualTo(ProviderKind.ANTHROPIC);
    }

    @Test
    void dashscope_model_uses_dashscope_config_not_openai() {
        // qwen-* should resolve to a DashScope (OPENAI compat) metadata that
        // reads DASHSCOPE_API_KEY and points at dashscope.aliyuncs.com.
        ProviderMetadata meta = Providers.metadata_for_model("qwen-plus").orElseThrow();
        assertThat(meta.provider()).isEqualTo(ProviderKind.OPENAI);
        assertThat(meta.auth_env()).isEqualTo("DASHSCOPE_API_KEY");
        assertThat(meta.default_base_url()).contains("dashscope.aliyuncs.com");
    }

    @Test
    void plugin_config_max_output_tokens_overrides_model_default() {
        // The Java port plumbs the override through max_tokens_for_model_with_override.
        // The full ConfigLoader integration lives in jclaude-runtime; here we verify
        // that an explicit override wins over the per-model default.
        Long plugin_override = 12_345L;
        long effective = Providers.max_tokens_for_model_with_override("claude-opus-4-6", plugin_override);

        assertThat(effective).isEqualTo(12_345L);
        assertThat(effective).isNotEqualTo(Providers.max_tokens_for_model("claude-opus-4-6"));
    }

    @Test
    void detects_provider_from_model_name_first() {
        assertThat(Providers.detect_provider_kind("grok")).isEqualTo(ProviderKind.XAI);
        assertThat(Providers.detect_provider_kind("claude-sonnet-4-6")).isEqualTo(ProviderKind.ANTHROPIC);
    }

    @Test
    void openai_namespaced_model_routes_to_openai_not_anthropic() {
        ProviderKind kind = Providers.metadata_for_model("openai/gpt-4.1-mini")
                .map(ProviderMetadata::provider)
                .orElseGet(() -> Providers.detect_provider_kind("openai/gpt-4.1-mini"));
        assertThat(kind).isEqualTo(ProviderKind.OPENAI);

        ProviderKind kind2 = Providers.metadata_for_model("gpt-4o")
                .map(ProviderMetadata::provider)
                .orElseGet(() -> Providers.detect_provider_kind("gpt-4o"));
        assertThat(kind2).isEqualTo(ProviderKind.OPENAI);
    }

    @Test
    void qwen_prefix_routes_to_dashscope_not_anthropic() {
        ProviderMetadata meta = Providers.metadata_for_model("qwen/qwen-max").orElseThrow();
        assertThat(meta.provider()).isEqualTo(ProviderKind.OPENAI);
        assertThat(meta.auth_env()).isEqualTo("DASHSCOPE_API_KEY");
        assertThat(meta.base_url_env()).isEqualTo("DASHSCOPE_BASE_URL");
        assertThat(meta.default_base_url()).contains("dashscope.aliyuncs.com");

        ProviderMetadata meta2 = Providers.metadata_for_model("qwen-plus").orElseThrow();
        assertThat(meta2.provider()).isEqualTo(ProviderKind.OPENAI);
        assertThat(meta2.auth_env()).isEqualTo("DASHSCOPE_API_KEY");

        assertThat(Providers.detect_provider_kind("qwen/qwen3-coder")).isEqualTo(ProviderKind.OPENAI);
    }

    @Test
    void kimi_prefix_routes_to_dashscope() {
        ProviderMetadata meta = Providers.metadata_for_model("kimi-k2.5").orElseThrow();
        assertThat(meta.auth_env()).isEqualTo("DASHSCOPE_API_KEY");
        assertThat(meta.base_url_env()).isEqualTo("DASHSCOPE_BASE_URL");
        assertThat(meta.default_base_url()).contains("dashscope.aliyuncs.com");
        assertThat(meta.provider()).isEqualTo(ProviderKind.OPENAI);

        ProviderMetadata meta2 = Providers.metadata_for_model("kimi/kimi-k2.5").orElseThrow();
        assertThat(meta2.auth_env()).isEqualTo("DASHSCOPE_API_KEY");
        assertThat(meta2.provider()).isEqualTo(ProviderKind.OPENAI);

        ProviderMetadata meta3 = Providers.metadata_for_model("kimi-k1.5").orElseThrow();
        assertThat(meta3.auth_env()).isEqualTo("DASHSCOPE_API_KEY");
    }

    @Test
    void kimi_alias_resolves_to_kimi_k2_5() {
        assertThat(Providers.resolve_model_alias("kimi")).isEqualTo("kimi-k2.5");
        assertThat(Providers.resolve_model_alias("KIMI")).isEqualTo("kimi-k2.5");
    }

    @Test
    void keeps_existing_max_token_heuristic() {
        assertThat(Providers.max_tokens_for_model("opus")).isEqualTo(32_000L);
        assertThat(Providers.max_tokens_for_model("grok-3")).isEqualTo(64_000L);
    }

    @Test
    void max_tokens_for_model_with_override_falls_back_when_plugin_unset() {
        long effective = Providers.max_tokens_for_model_with_override("claude-opus-4-6", null);
        assertThat(effective).isEqualTo(Providers.max_tokens_for_model("claude-opus-4-6"));
        assertThat(effective).isEqualTo(32_000L);
    }

    @Test
    void max_tokens_for_model_with_override_uses_plugin_override_when_set() {
        long effective = Providers.max_tokens_for_model_with_override("claude-opus-4-6", 12_345L);
        assertThat(effective).isEqualTo(12_345L);
        assertThat(effective).isNotEqualTo(Providers.max_tokens_for_model("claude-opus-4-6"));
    }

    @Test
    void returns_context_window_metadata_for_supported_models() {
        assertThat(Providers.model_token_limit("claude-sonnet-4-6")
                        .orElseThrow()
                        .context_window_tokens())
                .isEqualTo(200_000L);
        assertThat(Providers.model_token_limit("grok-mini").orElseThrow().context_window_tokens())
                .isEqualTo(131_072L);
    }

    @Test
    void preflight_blocks_requests_that_exceed_the_model_context_window() {
        MessageRequest request = MessageRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(64_000)
                .messages(List.of(InputMessage.user_text("x".repeat(600_000))))
                .system("Keep the answer short.")
                .tools(List.of(new ToolDefinition(
                        "weather",
                        "Fetches weather",
                        JclaudeMappers.standard()
                                .createObjectNode()
                                .put("type", "object")
                                .set(
                                        "properties",
                                        JclaudeMappers.standard()
                                                .createObjectNode()
                                                .set(
                                                        "city",
                                                        JclaudeMappers.standard()
                                                                .createObjectNode()
                                                                .put("type", "string"))))))
                .tool_choice(ToolChoice.auto())
                .stream(true)
                .build();

        Optional<Providers.ContextWindowExceededError> error = Providers.preflight_message_request(request);
        assertThat(error).isPresent();
        Providers.ContextWindowExceededError err = error.get();
        assertThat(err.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(err.estimated_input_tokens()).isGreaterThan(136_000L);
        assertThat(err.requested_output_tokens()).isEqualTo(64_000L);
        assertThat(err.estimated_total_tokens()).isGreaterThan(err.context_window_tokens());
        assertThat(err.context_window_tokens()).isEqualTo(200_000L);
    }

    @Test
    void preflight_skips_unknown_models() {
        MessageRequest request = MessageRequest.builder()
                .model("unknown-model")
                .max_tokens(64_000)
                .messages(List.of(InputMessage.user_text("x".repeat(600_000))))
                .build();
        assertThat(Providers.preflight_message_request(request)).isEmpty();
    }

    @Test
    void returns_context_window_metadata_for_kimi_models() {
        ModelTokenLimit k25 = Providers.model_token_limit("kimi-k2.5").orElseThrow();
        assertThat(k25.max_output_tokens()).isEqualTo(16_384L);
        assertThat(k25.context_window_tokens()).isEqualTo(256_000L);

        ModelTokenLimit k15 = Providers.model_token_limit("kimi-k1.5").orElseThrow();
        assertThat(k15.max_output_tokens()).isEqualTo(16_384L);
        assertThat(k15.context_window_tokens()).isEqualTo(256_000L);
    }

    @Test
    void kimi_alias_resolves_to_kimi_k25_token_limits() {
        ModelTokenLimit alias = Providers.model_token_limit("kimi").orElseThrow();
        ModelTokenLimit direct = Providers.model_token_limit("kimi-k2.5").orElseThrow();
        assertThat(alias.max_output_tokens()).isEqualTo(direct.max_output_tokens());
        assertThat(alias.context_window_tokens()).isEqualTo(direct.context_window_tokens());
    }

    @Test
    void preflight_blocks_oversized_requests_for_kimi_models() {
        MessageRequest request = MessageRequest.builder()
                .model("kimi-k2.5")
                .max_tokens(16_384)
                .messages(List.of(InputMessage.user_text("x".repeat(1_000_000))))
                .system("Keep the answer short.")
                .stream(true)
                .build();

        Providers.ContextWindowExceededError err =
                Providers.preflight_message_request(request).orElseThrow();
        assertThat(err.model()).isEqualTo("kimi-k2.5");
        assertThat(err.context_window_tokens()).isEqualTo(256_000L);
    }

    @Test
    void parse_dotenv_extracts_keys_handles_comments_quotes_and_export_prefix() {
        String body = "# this is a comment\n"
                + "\n"
                + "ANTHROPIC_API_KEY=plain-value\n"
                + "XAI_API_KEY=\"quoted-value\"\n"
                + "OPENAI_API_KEY='single-quoted'\n"
                + "export GROK_API_KEY=exported-value\n"
                + "   PADDED_KEY  =  padded-value  \n"
                + "EMPTY_VALUE=\n"
                + "NO_EQUALS_LINE\n";

        Map<String, String> values = Providers.parse_dotenv(body);

        assertThat(values).containsEntry("ANTHROPIC_API_KEY", "plain-value");
        assertThat(values).containsEntry("XAI_API_KEY", "quoted-value");
        assertThat(values).containsEntry("OPENAI_API_KEY", "single-quoted");
        assertThat(values).containsEntry("GROK_API_KEY", "exported-value");
        assertThat(values).containsEntry("PADDED_KEY", "padded-value");
        assertThat(values).containsEntry("EMPTY_VALUE", "");
        assertThat(values).doesNotContainKey("NO_EQUALS_LINE");
        assertThat(values).doesNotContainKey("# this is a comment");
    }

    @Test
    void load_dotenv_file_reads_keys_from_disk_and_returns_none_when_missing(@TempDir Path tempDir) throws IOException {
        Path env_path = tempDir.resolve(".env");
        Files.writeString(
                env_path,
                "ANTHROPIC_API_KEY=secret-from-file\n# comment\nXAI_API_KEY=\"xai-secret\"\n",
                StandardCharsets.UTF_8);
        Path missing_path = tempDir.resolve("does-not-exist.env");

        Map<String, String> loaded = Providers.load_dotenv_file(env_path).orElseThrow();
        Optional<Map<String, String>> missing = Providers.load_dotenv_file(missing_path);

        assertThat(loaded).containsEntry("ANTHROPIC_API_KEY", "secret-from-file");
        assertThat(loaded).containsEntry("XAI_API_KEY", "xai-secret");
        assertThat(missing).isEmpty();
    }

    @Test
    void anthropic_missing_credentials_hint_is_none_when_no_foreign_creds_present() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "");

        assertThat(Providers.anthropic_missing_credentials_hint()).isEmpty();
    }

    @Test
    void anthropic_missing_credentials_hint_detects_openai_api_key_and_recommends_openai_prefix() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "sk-openrouter-varleg");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "");

        String hint = Providers.anthropic_missing_credentials_hint().orElseThrow();

        assertThat(hint).contains("OPENAI_API_KEY is set");
        assertThat(hint).contains("OpenAI-compat");
        assertThat(hint).contains("openai/");
        assertThat(hint).contains("OPENAI_BASE_URL");
    }

    @Test
    void anthropic_missing_credentials_hint_detects_xai_api_key() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "xai-test-key");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "");

        String hint = Providers.anthropic_missing_credentials_hint().orElseThrow();

        assertThat(hint).contains("XAI_API_KEY is set");
        assertThat(hint).contains("xAI");
        assertThat(hint).contains("grok");
    }

    @Test
    void anthropic_missing_credentials_hint_detects_dashscope_api_key() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "sk-dashscope-test");

        String hint = Providers.anthropic_missing_credentials_hint().orElseThrow();

        assertThat(hint).contains("DASHSCOPE_API_KEY is set");
        assertThat(hint).contains("DashScope");
        assertThat(hint).contains("qwen");
    }

    @Test
    void anthropic_missing_credentials_hint_prefers_openai_when_multiple_foreign_creds_set() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "sk-openrouter-varleg");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "xai-test-key");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "sk-dashscope-test");

        String hint = Providers.anthropic_missing_credentials_hint().orElseThrow();

        assertThat(hint).contains("OPENAI_API_KEY");
        assertThat(hint).doesNotContain("XAI_API_KEY");
    }

    @Test
    void anthropic_missing_credentials_hint_ignores_empty_string_values() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("DASHSCOPE_API_KEY", "");

        assertThat(Providers.anthropic_missing_credentials_hint()).isEmpty();
    }

    @Test
    void openai_base_url_overrides_anthropic_fallback_for_unknown_model() {
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_BASE_URL", "http://127.0.0.1:11434/v1");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("OPENAI_API_KEY", "dummy");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("ANTHROPIC_API_KEY", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("ANTHROPIC_AUTH_TOKEN", "");
        Providers.ENV_OVERRIDES_FOR_TESTING.put("XAI_API_KEY", "");

        ProviderKind provider = Providers.detect_provider_kind("qwen2.5-coder:7b");
        assertThat(provider).isEqualTo(ProviderKind.OPENAI);
    }
}
