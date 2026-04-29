package org.jclaude.api.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.MessageRequest;

/**
 * Provider routing, alias resolution, env-var lookup and request preflight
 * helpers. Java port of {@code crates/api/src/providers/mod.rs} from the Rust
 * codebase.
 *
 * <p>The {@link #MODEL_REGISTRY} captures the list of supported model alias
 * mappings. Use {@link #resolve_model_alias(String)} to canonicalise a user
 * supplied model id, {@link #metadata_for_model(String)} to look up the
 * provider routing, and {@link #detect_provider_kind(String)} for the auth
 * sniffer fallback.
 */
public final class Providers {

    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    /**
     * Static alias→metadata table. The order is significant for the alias
     * resolver — entries are scanned top-to-bottom and the first match wins.
     */
    static final List<Map.Entry<String, ProviderMetadata>> MODEL_REGISTRY = List.of(
            Map.entry(
                    "opus",
                    new ProviderMetadata(
                            ProviderKind.ANTHROPIC,
                            "ANTHROPIC_API_KEY",
                            "ANTHROPIC_BASE_URL",
                            DEFAULT_ANTHROPIC_BASE_URL)),
            Map.entry(
                    "sonnet",
                    new ProviderMetadata(
                            ProviderKind.ANTHROPIC,
                            "ANTHROPIC_API_KEY",
                            "ANTHROPIC_BASE_URL",
                            DEFAULT_ANTHROPIC_BASE_URL)),
            Map.entry(
                    "haiku",
                    new ProviderMetadata(
                            ProviderKind.ANTHROPIC,
                            "ANTHROPIC_API_KEY",
                            "ANTHROPIC_BASE_URL",
                            DEFAULT_ANTHROPIC_BASE_URL)),
            Map.entry(
                    "grok",
                    new ProviderMetadata(
                            ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL)),
            Map.entry(
                    "grok-3",
                    new ProviderMetadata(
                            ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL)),
            Map.entry(
                    "grok-mini",
                    new ProviderMetadata(
                            ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL)),
            Map.entry(
                    "grok-3-mini",
                    new ProviderMetadata(
                            ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL)),
            Map.entry(
                    "grok-2",
                    new ProviderMetadata(
                            ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL)),
            Map.entry(
                    "kimi",
                    new ProviderMetadata(
                            ProviderKind.OPENAI,
                            "DASHSCOPE_API_KEY",
                            "DASHSCOPE_BASE_URL",
                            OpenAiCompatClient.DEFAULT_DASHSCOPE_BASE_URL)));

    /** First entry wins when sniffing for foreign credentials in a "missing Anthropic creds" error. */
    static final List<ForeignProviderHint> FOREIGN_PROVIDER_ENV_VARS = List.of(
            new ForeignProviderHint(
                    "OPENAI_API_KEY",
                    "OpenAI-compat",
                    "prefix your model name with `openai/` (e.g. `--model openai/gpt-4.1-mini`) so prefix routing selects the OpenAI-compatible provider, and set `OPENAI_BASE_URL` if you are pointing at OpenRouter/Ollama/a local server"),
            new ForeignProviderHint(
                    "XAI_API_KEY",
                    "xAI",
                    "use an xAI model alias (e.g. `--model grok` or `--model grok-mini`) so the prefix router selects the xAI backend"),
            new ForeignProviderHint(
                    "DASHSCOPE_API_KEY",
                    "Alibaba DashScope",
                    "prefix your model name with `qwen/` or `qwen-` (e.g. `--model qwen-plus`) so prefix routing selects the DashScope backend"));

    private Providers() {}

    /**
     * Resolve a user-friendly model alias (e.g. {@code "opus"}, {@code "grok"})
     * to the canonical model id used on the wire. Unknown values are returned
     * unchanged after trimming.
     */
    public static String resolve_model_alias(String model) {
        Objects.requireNonNull(model, "model");
        String trimmed = model.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, ProviderMetadata> entry : MODEL_REGISTRY) {
            String alias = entry.getKey();
            if (!alias.equals(lower)) {
                continue;
            }
            ProviderMetadata metadata = entry.getValue();
            return switch (metadata.provider()) {
                case ANTHROPIC -> switch (alias) {
                    case "opus" -> "claude-opus-4-6";
                    case "sonnet" -> "claude-sonnet-4-6";
                    case "haiku" -> "claude-haiku-4-5-20251213";
                    default -> trimmed;
                };
                case XAI -> switch (alias) {
                    case "grok", "grok-3" -> "grok-3";
                    case "grok-mini", "grok-3-mini" -> "grok-3-mini";
                    case "grok-2" -> "grok-2";
                    default -> trimmed;
                };
                case OPENAI -> switch (alias) {
                    case "kimi" -> "kimi-k2.5";
                    default -> trimmed;
                };
            };
        }
        return trimmed;
    }

    /** Look up provider routing metadata for a model, after alias resolution. */
    public static Optional<ProviderMetadata> metadata_for_model(String model) {
        String canonical = resolve_model_alias(model);
        if (canonical.startsWith("claude")) {
            return Optional.of(new ProviderMetadata(
                    ProviderKind.ANTHROPIC, "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", DEFAULT_ANTHROPIC_BASE_URL));
        }
        if (canonical.startsWith("grok")) {
            return Optional.of(new ProviderMetadata(
                    ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL));
        }
        // openai/ prefix or bare gpt- prefix routes to OpenAI regardless of which
        // auth env vars are set, so prefix-routing wins over auth-sniffer order.
        if (canonical.startsWith("openai/") || canonical.startsWith("gpt-")) {
            return Optional.of(new ProviderMetadata(
                    ProviderKind.OPENAI,
                    "OPENAI_API_KEY",
                    "OPENAI_BASE_URL",
                    OpenAiCompatClient.DEFAULT_OPENAI_BASE_URL));
        }
        // qwen/* and qwen-* models route to Alibaba DashScope (OpenAI compat
        // shape, only base URL and auth env var differ).
        if (canonical.startsWith("qwen/") || canonical.startsWith("qwen-")) {
            return Optional.of(new ProviderMetadata(
                    ProviderKind.OPENAI,
                    "DASHSCOPE_API_KEY",
                    "DASHSCOPE_BASE_URL",
                    OpenAiCompatClient.DEFAULT_DASHSCOPE_BASE_URL));
        }
        // kimi/* and kimi-* via DashScope compatible-mode.
        if (canonical.startsWith("kimi/") || canonical.startsWith("kimi-")) {
            return Optional.of(new ProviderMetadata(
                    ProviderKind.OPENAI,
                    "DASHSCOPE_API_KEY",
                    "DASHSCOPE_BASE_URL",
                    OpenAiCompatClient.DEFAULT_DASHSCOPE_BASE_URL));
        }
        return Optional.empty();
    }

    /**
     * Determine the routing provider for {@code model}. Falls back to env-var
     * sniffing in a fixed order when no metadata is found.
     */
    public static ProviderKind detect_provider_kind(String model) {
        Optional<ProviderMetadata> metadata = metadata_for_model(model);
        if (metadata.isPresent()) {
            return metadata.get().provider();
        }
        // OPENAI_BASE_URL set + OPENAI_API_KEY set → user explicitly configured an
        // OpenAI-compatible endpoint (Ollama, LM Studio, vLLM, OpenRouter, etc.).
        if (env_var_present_non_empty("OPENAI_BASE_URL") && OpenAiCompatClient.has_api_key("OPENAI_API_KEY")) {
            return ProviderKind.OPENAI;
        }
        // Anthropic auth in env or saved → route to Anthropic.
        if (anthropic_has_auth()) {
            return ProviderKind.ANTHROPIC;
        }
        if (OpenAiCompatClient.has_api_key("OPENAI_API_KEY")) {
            return ProviderKind.OPENAI;
        }
        if (OpenAiCompatClient.has_api_key("XAI_API_KEY")) {
            return ProviderKind.XAI;
        }
        // Last resort: OPENAI_BASE_URL alone (some local providers don't need auth).
        if (env_var_present_non_empty("OPENAI_BASE_URL")) {
            return ProviderKind.OPENAI;
        }
        return ProviderKind.ANTHROPIC;
    }

    /**
     * Effective max output tokens for {@code model}. Falls back to a heuristic
     * based on whether the canonical model id contains "opus" when no exact
     * registry entry exists.
     */
    public static long max_tokens_for_model(String model) {
        return model_token_limit(model).map(ModelTokenLimit::max_output_tokens).orElseGet(() -> {
            String canonical = resolve_model_alias(model);
            return canonical.contains("opus") ? 32_000L : 64_000L;
        });
    }

    /** Effective max output tokens, preferring the plugin override when set. */
    public static long max_tokens_for_model_with_override(String model, Long plugin_override) {
        return plugin_override == null ? max_tokens_for_model(model) : plugin_override;
    }

    /** Returns per-model context-window metadata when registered. */
    public static Optional<ModelTokenLimit> model_token_limit(String model) {
        String canonical = resolve_model_alias(model);
        return switch (canonical) {
            case "claude-opus-4-6" -> Optional.of(new ModelTokenLimit(32_000, 200_000));
            case "claude-sonnet-4-6", "claude-haiku-4-5-20251213" -> Optional.of(new ModelTokenLimit(64_000, 200_000));
            case "grok-3", "grok-3-mini" -> Optional.of(new ModelTokenLimit(64_000, 131_072));
            case "kimi-k2.5", "kimi-k1.5" -> Optional.of(new ModelTokenLimit(16_384, 256_000));
            default -> Optional.empty();
        };
    }

    /**
     * Estimate the request body size and reject before the upstream call when
     * the projected total exceeds the model context window. Returns
     * {@code null} when no registered limit applies, or a populated
     * {@link ContextWindowExceededError} when the request is oversized.
     */
    public static Optional<ContextWindowExceededError> preflight_message_request(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<ModelTokenLimit> limit = model_token_limit(request.model());
        if (limit.isEmpty()) {
            return Optional.empty();
        }
        long estimated_input_tokens = estimate_message_request_input_tokens(request);
        long estimated_total_tokens = Math.addExact(
                Math.min(Long.MAX_VALUE - request.max_tokens(), estimated_input_tokens), request.max_tokens());
        long context_window = limit.get().context_window_tokens();
        if (estimated_total_tokens > context_window) {
            return Optional.of(new ContextWindowExceededError(
                    resolve_model_alias(request.model()),
                    estimated_input_tokens,
                    request.max_tokens(),
                    estimated_total_tokens,
                    context_window));
        }
        return Optional.empty();
    }

    static long estimate_message_request_input_tokens(MessageRequest request) {
        long estimate = estimate_serialized_tokens(request.messages());
        estimate = saturating_add(estimate, estimate_serialized_tokens(request.system()));
        estimate = saturating_add(estimate, estimate_serialized_tokens(request.tools()));
        estimate = saturating_add(estimate, estimate_serialized_tokens(request.tool_choice()));
        return estimate;
    }

    private static long estimate_serialized_tokens(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            return (bytes.length / 4L) + 1L;
        } catch (JsonProcessingException error) {
            return 0;
        }
    }

    private static long saturating_add(long left, long right) {
        long result = left + right;
        // u32 saturate (Rust uses u32 for these counters, but Java uses signed long).
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    /**
     * Build a hint string identifying the first foreign-provider credential
     * present in the environment when an Anthropic credential lookup has just
     * failed. Returns {@code Optional.empty()} when no foreign credential is
     * set so the caller can emit a plain "missing Anthropic credentials"
     * message instead.
     */
    public static Optional<String> anthropic_missing_credentials_hint() {
        for (ForeignProviderHint hint : FOREIGN_PROVIDER_ENV_VARS) {
            if (env_or_dotenv_present(hint.env_var())) {
                return Optional.of("I see " + hint.env_var() + " is set — if you meant to use the "
                        + hint.provider_label() + " provider, " + hint.fix_hint() + ".");
            }
        }
        return Optional.empty();
    }

    /**
     * Parse a {@code .env} file body into key/value pairs using a minimal
     * {@code KEY=VALUE} grammar. Lines that are blank, start with {@code #},
     * or do not contain {@code =} are ignored. Surrounding double or single
     * quotes are stripped. An optional leading {@code export } prefix on the
     * key is also stripped.
     */
    public static Map<String, String> parse_dotenv(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (content == null) {
            return values;
        }
        for (String raw_line : content.split("\n", -1)) {
            String line = raw_line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String raw_key = line.substring(0, equals);
            String raw_value = line.substring(equals + 1);
            String trimmed_key = raw_key.strip();
            String key = trimmed_key.startsWith("export ")
                    ? trimmed_key.substring("export ".length()).strip()
                    : trimmed_key;
            if (key.isEmpty()) {
                continue;
            }
            String trimmed_value = raw_value.strip();
            String unquoted = trimmed_value;
            if (trimmed_value.length() >= 2) {
                if ((trimmed_value.startsWith("\"") && trimmed_value.endsWith("\""))
                        || (trimmed_value.startsWith("'") && trimmed_value.endsWith("'"))) {
                    unquoted = trimmed_value.substring(1, trimmed_value.length() - 1);
                }
            }
            values.put(key, unquoted);
        }
        return values;
    }

    /** Load and parse a {@code .env} file at {@code path}. */
    public static Optional<Map<String, String>> load_dotenv_file(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(parse_dotenv(content));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /**
     * Look up {@code key} in a {@code .env} file located in the current
     * working directory. Returns {@link Optional#empty()} when the file is
     * missing, the key is absent, or the value is empty.
     */
    public static Optional<String> dotenv_value(String key) {
        Path cwd;
        try {
            cwd = Paths.get("").toAbsolutePath();
        } catch (Exception error) {
            return Optional.empty();
        }
        return load_dotenv_file(cwd.resolve(".env"))
                .flatMap(values -> Optional.ofNullable(values.get(key)).filter(value -> !value.isEmpty()));
    }

    /**
     * Check whether {@code key} is set to a non-empty value either in the real
     * process environment or in the working-directory {@code .env} file.
     */
    static boolean env_or_dotenv_present(String key) {
        if (ENV_OVERRIDES_FOR_TESTING.containsKey(key)) {
            String override = ENV_OVERRIDES_FOR_TESTING.get(key);
            if (override != null && !override.isEmpty()) {
                return true;
            }
            return dotenv_value(key).filter(v -> !v.isEmpty()).isPresent();
        }
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return true;
        }
        return dotenv_value(key).filter(v -> !v.isEmpty()).isPresent();
    }

    /** Return true when an env var is present and non-empty in the live process env. */
    static boolean env_var_present_non_empty(String key) {
        String override = ENV_OVERRIDES_FOR_TESTING.get(key);
        if (override != null) {
            return !override.isEmpty();
        }
        if (ENV_OVERRIDES_FOR_TESTING.containsKey(key)) {
            // explicit null override means "unset"
            return false;
        }
        String value = System.getenv(key);
        return value != null && !value.isEmpty();
    }

    /**
     * Attempt to read a non-empty env value, falling back to the working
     * directory {@code .env} file. Mirrors the Rust {@code read_env_non_empty}
     * helper used by the OpenAI-compat client.
     */
    public static Optional<String> read_env_non_empty(String key) {
        if (ENV_OVERRIDES_FOR_TESTING.containsKey(key)) {
            String override = ENV_OVERRIDES_FOR_TESTING.get(key);
            if (override == null || override.isEmpty()) {
                return dotenv_value(key);
            }
            return Optional.of(override);
        }
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return Optional.of(value);
        }
        return dotenv_value(key);
    }

    /**
     * Predicate equivalent of {@link #read_env_non_empty(String)} — returns
     * true when the env var or the {@code .env} file holds a non-empty value.
     */
    public static boolean has_api_key(String key) {
        return read_env_non_empty(key).isPresent();
    }

    /**
     * Whether the environment carries an Anthropic credential. Mirrors the
     * Rust {@code anthropic::has_auth_from_env_or_saved} guard used by the
     * provider sniffer (saved-token discovery is delegated to the Anthropic
     * client port — for now the env-only path is sufficient for the foreign
     * credential hint sniffer).
     */
    static boolean anthropic_has_auth() {
        return env_or_dotenv_present("ANTHROPIC_AUTH_TOKEN") || env_or_dotenv_present("ANTHROPIC_API_KEY");
    }

    /**
     * Result of a {@link #preflight_message_request(MessageRequest)} call when
     * the projected total tokens exceed the registered context window.
     */
    public record ContextWindowExceededError(
            String model,
            long estimated_input_tokens,
            long requested_output_tokens,
            long estimated_total_tokens,
            long context_window_tokens) {

        /** Human-readable rendering useful for error messages and tests. */
        public String to_message() {
            return "context window exceeded for model " + model + ": estimated_input_tokens="
                    + estimated_input_tokens + " requested_output_tokens=" + requested_output_tokens
                    + " estimated_total_tokens=" + estimated_total_tokens + " context_window_tokens="
                    + context_window_tokens;
        }
    }

    /** Foreign-provider hint table entry. */
    record ForeignProviderHint(String env_var, String provider_label, String fix_hint) {}

    /**
     * Convenience helper used by tests to swap an env var for a scoped block.
     * Java tests cannot easily mutate {@link System#getenv()} in-process, so
     * tests use this map to stage values that
     * {@link #env_var_present_non_empty(String)} consults first.
     *
     * <p>This is package private and only exists for the unit-test port — the
     * production code path goes through {@link System#getenv(String)} only.
     */
    static final Map<String, String> ENV_OVERRIDES_FOR_TESTING = new HashMap<>();
}
