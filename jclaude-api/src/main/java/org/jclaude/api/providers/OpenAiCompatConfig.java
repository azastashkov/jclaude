package org.jclaude.api.providers;

import java.util.List;

/**
 * Configuration for an OpenAI-compatible provider — the human-readable
 * provider name, the env var that carries the API key, the env var used to
 * override the base URL, the default base URL, and the maximum request body
 * size accepted by the upstream service.
 *
 * <p>Java port of the Rust {@code OpenAiCompatConfig}. Use the static
 * factories {@link #xai()}, {@link #openai()} or {@link #dashscope()} for the
 * three built-in providers.
 */
public record OpenAiCompatConfig(
        String provider_name,
        String api_key_env,
        String base_url_env,
        String default_base_url,
        int max_request_body_bytes) {

    private static final List<String> XAI_ENV_VARS = List.of("XAI_API_KEY");
    private static final List<String> OPENAI_ENV_VARS = List.of("OPENAI_API_KEY");
    private static final List<String> DASHSCOPE_ENV_VARS = List.of("DASHSCOPE_API_KEY");

    /** xAI request body limit — observed 50MB cap on /chat/completions. */
    public static final int XAI_MAX_REQUEST_BODY_BYTES = 52_428_800;

    /** OpenAI request body limit — 100MB. */
    public static final int OPENAI_MAX_REQUEST_BODY_BYTES = 104_857_600;

    /** DashScope compatible-mode request body limit — observed 6MB cap. */
    public static final int DASHSCOPE_MAX_REQUEST_BODY_BYTES = 6_291_456;

    /** Convenience factory producing the xAI default config. */
    public static OpenAiCompatConfig xai() {
        return new OpenAiCompatConfig(
                "xAI",
                "XAI_API_KEY",
                "XAI_BASE_URL",
                OpenAiCompatClient.DEFAULT_XAI_BASE_URL,
                XAI_MAX_REQUEST_BODY_BYTES);
    }

    /** Convenience factory producing the OpenAI default config. */
    public static OpenAiCompatConfig openai() {
        return new OpenAiCompatConfig(
                "OpenAI",
                "OPENAI_API_KEY",
                "OPENAI_BASE_URL",
                OpenAiCompatClient.DEFAULT_OPENAI_BASE_URL,
                OPENAI_MAX_REQUEST_BODY_BYTES);
    }

    /**
     * Convenience factory for Alibaba DashScope's OpenAI compatible-mode
     * endpoint (qwen / kimi family models).
     */
    public static OpenAiCompatConfig dashscope() {
        return new OpenAiCompatConfig(
                "DashScope",
                "DASHSCOPE_API_KEY",
                "DASHSCOPE_BASE_URL",
                OpenAiCompatClient.DEFAULT_DASHSCOPE_BASE_URL,
                DASHSCOPE_MAX_REQUEST_BODY_BYTES);
    }

    /** Env-var list for credential discovery hints. */
    public List<String> credential_env_vars() {
        return switch (provider_name) {
            case "xAI" -> XAI_ENV_VARS;
            case "OpenAI" -> OPENAI_ENV_VARS;
            case "DashScope" -> DASHSCOPE_ENV_VARS;
            default -> List.of();
        };
    }
}
