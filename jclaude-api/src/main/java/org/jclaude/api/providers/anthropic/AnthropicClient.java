package org.jclaude.api.providers.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.sse.SseStreamReader;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.MessageResponse;
import org.jclaude.api.types.StreamEvent;

/**
 * Native Anthropic HTTP client.
 *
 * <p>Java port of {@code crates/api/src/providers/anthropic.rs}. The
 * {@link #send_message(MessageRequest)} entry point does a non-streaming
 * POST to {@code /v1/messages}, {@link #stream_message(MessageRequest)}
 * returns an iterator over SSE-delivered {@link StreamEvent}s, and
 * {@link #count_tokens(MessageRequest)} hits {@code /v1/messages/count_tokens}.
 *
 * <p>Authentication is delivered via {@code x-api-key} (when an api key is
 * set) and/or {@code authorization: Bearer ...} (when an OAuth token is set).
 * Both can be sent at once for proxy scenarios — this matches the Rust
 * {@code AuthSource::ApiKeyAndBearer} variant. {@code anthropic-version},
 * {@code anthropic-beta}, and {@code user-agent} are emitted on every
 * request.
 */
public final class AnthropicClient {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();
    private static final String REQUEST_ID_HEADER = "request-id";
    private static final String ALT_REQUEST_ID_HEADER = "x-request-id";

    private final HttpClient http;
    private final AnthropicConfig config;
    private final PromptCache prompt_cache;

    public AnthropicClient(AnthropicConfig config) {
        this(default_http_client(config), config, null);
    }

    public AnthropicClient(AnthropicConfig config, PromptCache prompt_cache) {
        this(default_http_client(config), config, prompt_cache);
    }

    public AnthropicClient(HttpClient http, AnthropicConfig config) {
        this(http, config, null);
    }

    public AnthropicClient(HttpClient http, AnthropicConfig config, PromptCache prompt_cache) {
        this.http = Objects.requireNonNull(http, "http");
        this.config = Objects.requireNonNull(config, "config");
        this.prompt_cache = prompt_cache;
        if (config.api_key() == null && config.auth_token() == null) {
            // Allow construction without credentials for tests that only
            // exercise URL/header building. The first request will surface
            // the missing-credentials error.
        }
    }

    private static HttpClient default_http_client(AnthropicConfig config) {
        return HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    public AnthropicConfig config() {
        return config;
    }

    public Optional<PromptCache> prompt_cache() {
        return Optional.ofNullable(prompt_cache);
    }

    /** Non-streaming POST {@code /v1/messages}. */
    public MessageResponse send_message(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        MessageRequest non_streaming = with_streaming(request, false);
        HttpResponse<String> response = send_with_retry_string("/v1/messages", non_streaming);
        Optional<String> request_id = request_id_from_headers(response.headers());
        String body = response.body() == null ? "" : response.body();
        try {
            MessageResponse parsed = MAPPER.readValue(body, MessageResponse.class);
            MessageResponse with_id = parsed.request_id() == null && request_id.isPresent()
                    ? new MessageResponse(
                            parsed.id(),
                            parsed.kind(),
                            parsed.role(),
                            parsed.content(),
                            parsed.model(),
                            parsed.stop_reason(),
                            parsed.stop_sequence(),
                            parsed.usage(),
                            request_id.get())
                    : parsed;
            if (prompt_cache != null) {
                prompt_cache.record_response(with_id);
            }
            return with_id;
        } catch (IOException error) {
            throw AnthropicException.json_deserialize(non_streaming.model(), body, error);
        }
    }

    /** Streaming POST {@code /v1/messages}. */
    public StreamingResponse stream_message(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        MessageRequest streaming = with_streaming(request, true);
        HttpResponse<InputStream> response = send_with_retry_stream("/v1/messages", streaming);
        Optional<String> request_id = request_id_from_headers(response.headers());
        return new StreamingResponse(
                request_id.orElse(null), new StreamEventIterator(response.body(), streaming.model(), prompt_cache));
    }

    /** POST {@code /v1/messages/count_tokens} returning the {@code input_tokens} count. */
    public long count_tokens(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        HttpResponse<String> response = send_with_retry_string("/v1/messages/count_tokens", request);
        String body = response.body() == null ? "" : response.body();
        try {
            JsonNode parsed = MAPPER.readTree(body);
            JsonNode input_tokens = parsed.get("input_tokens");
            if (input_tokens == null || !input_tokens.canConvertToLong()) {
                throw AnthropicException.json_deserialize(
                        request.model(), body, new IllegalArgumentException("missing input_tokens field"));
            }
            return input_tokens.asLong();
        } catch (IOException error) {
            throw AnthropicException.json_deserialize(request.model(), body, error);
        }
    }

    /** Streaming response wrapper holding the request id and the iterator of events. */
    public static final class StreamingResponse implements Iterable<StreamEvent>, AutoCloseable {

        private final String request_id;
        private final StreamEventIterator iterator;

        public StreamingResponse(String request_id, StreamEventIterator iterator) {
            this.request_id = request_id;
            this.iterator = iterator;
        }

        public Optional<String> request_id() {
            return Optional.ofNullable(request_id);
        }

        @Override
        public Iterator<StreamEvent> iterator() {
            return iterator;
        }

        @Override
        public void close() {
            iterator.close();
        }
    }

    /**
     * Iterator that consumes a SSE-formatted {@link InputStream} and yields
     * decoded Anthropic {@link StreamEvent}s. Mirrors the Rust
     * {@code MessageStream}.
     */
    public static final class StreamEventIterator implements Iterator<StreamEvent>, AutoCloseable {

        private final InputStream input;
        private final SseStreamReader reader;
        private final PromptCache prompt_cache;
        private final Deque<StreamEvent> pending = new ArrayDeque<>();
        private boolean done;
        private boolean closed;
        private byte[] readBuffer = new byte[8192];

        StreamEventIterator(InputStream input, String model, PromptCache prompt_cache) {
            this.input = input;
            this.reader = new SseStreamReader("Anthropic", model);
            this.prompt_cache = prompt_cache;
        }

        @Override
        public boolean hasNext() {
            ensure_pending();
            return !pending.isEmpty();
        }

        @Override
        public StreamEvent next() {
            ensure_pending();
            if (pending.isEmpty()) {
                throw new NoSuchElementException();
            }
            StreamEvent event = pending.removeFirst();
            observe(event);
            return event;
        }

        private void observe(StreamEvent event) {
            if (prompt_cache == null) {
                return;
            }
            if (event instanceof StreamEvent.MessageDelta delta && delta.usage() != null) {
                prompt_cache.record_usage(delta.usage());
            } else if (event instanceof StreamEvent.MessageStart start
                    && start.message() != null
                    && start.message().usage() != null) {
                prompt_cache.record_usage(start.message().usage());
            }
        }

        private void ensure_pending() {
            while (pending.isEmpty()) {
                if (done) {
                    pending.addAll(reader.finishTyped());
                    return;
                }
                int read;
                try {
                    read = input.read(readBuffer);
                } catch (IOException error) {
                    throw AnthropicException.io("failed to read stream chunk", error);
                }
                if (read < 0) {
                    done = true;
                    pending.addAll(reader.finishTyped());
                    return;
                }
                if (read == 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(readBuffer, 0, chunk, 0, read);
                pending.addAll(reader.pushTypedBytes(chunk));
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                input.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static MessageRequest with_streaming(MessageRequest request, boolean streaming) {
        if (request.stream() == streaming) {
            return request;
        }
        return new MessageRequest(
                request.model(),
                request.max_tokens(),
                request.messages(),
                request.system(),
                request.tools(),
                request.tool_choice(),
                streaming,
                request.temperature(),
                request.top_p(),
                request.frequency_penalty(),
                request.presence_penalty(),
                request.stop(),
                request.reasoning_effort());
    }

    private HttpResponse<String> send_with_retry_string(String endpoint, MessageRequest request) {
        int attempts = 0;
        AnthropicException last_error;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> response = send_raw_request_string(endpoint, request);
                Optional<AnthropicException> failure = expect_success_string(response);
                if (failure.isEmpty()) {
                    return response;
                }
                if (failure.get().is_retryable() && attempts <= config.max_retries() + 1) {
                    last_error = failure.get();
                } else {
                    throw failure.get();
                }
            } catch (AnthropicException error) {
                if (error.is_retryable() && attempts <= config.max_retries() + 1) {
                    last_error = error;
                } else {
                    throw error;
                }
            }
            if (attempts > config.max_retries()) {
                throw AnthropicException.retries_exhausted(attempts, last_error);
            }
            sleep_for_jittered_backoff(attempts);
        }
    }

    private HttpResponse<InputStream> send_with_retry_stream(String endpoint, MessageRequest request) {
        int attempts = 0;
        AnthropicException last_error;
        while (true) {
            attempts++;
            try {
                HttpResponse<InputStream> response = send_raw_request_stream(endpoint, request);
                Optional<AnthropicException> failure = expect_success_stream(response);
                if (failure.isEmpty()) {
                    return response;
                }
                if (failure.get().is_retryable() && attempts <= config.max_retries() + 1) {
                    last_error = failure.get();
                } else {
                    throw failure.get();
                }
            } catch (AnthropicException error) {
                if (error.is_retryable() && attempts <= config.max_retries() + 1) {
                    last_error = error;
                } else {
                    throw error;
                }
            }
            if (attempts > config.max_retries()) {
                throw AnthropicException.retries_exhausted(attempts, last_error);
            }
            sleep_for_jittered_backoff(attempts);
        }
    }

    private HttpResponse<String> send_raw_request_string(String endpoint, MessageRequest request) {
        ensure_credentials();
        HttpRequest http_request = build_http_request(endpoint, request);
        try {
            return http.send(http_request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw AnthropicException.io("failed to dispatch request to " + http_request.uri(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw AnthropicException.io("request interrupted", error);
        }
    }

    private HttpResponse<InputStream> send_raw_request_stream(String endpoint, MessageRequest request) {
        ensure_credentials();
        HttpRequest http_request = build_http_request(endpoint, request);
        try {
            return http.send(http_request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw AnthropicException.io("failed to dispatch request to " + http_request.uri(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw AnthropicException.io("request interrupted", error);
        }
    }

    private void ensure_credentials() {
        if (config.api_key() == null && config.auth_token() == null) {
            throw AnthropicException.missing_credentials();
        }
    }

    HttpRequest build_http_request(String endpoint, MessageRequest request) {
        URI uri = URI.create(messages_endpoint(config.base_url(), endpoint));
        ObjectNode payload = render_request_body(request);
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (IOException error) {
            throw AnthropicException.io("failed to serialize request payload", error);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("content-type", "application/json")
                .header("anthropic-version", config.anthropic_version())
                .header("user-agent", config.user_agent())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (config.api_key() != null) {
            builder.header("x-api-key", config.api_key());
        }
        if (config.auth_token() != null) {
            builder.header("authorization", "Bearer " + config.auth_token());
        }
        if (!config.betas().isEmpty()) {
            builder.header("anthropic-beta", String.join(",", config.betas()));
        }
        return builder.build();
    }

    /**
     * Build the JSON body. The {@code anthropic-beta} list flows via the
     * header, never the body — strip {@code betas} from the request payload
     * (and the OpenAI-only {@code frequency_penalty}/{@code presence_penalty}
     * fields, plus rename {@code stop} → {@code stop_sequences}). Mirrors the
     * Rust {@code strip_unsupported_beta_body_fields} helper.
     */
    static ObjectNode render_request_body(MessageRequest request) {
        ObjectNode body = MAPPER.valueToTree(request);
        body.remove("betas");
        body.remove("frequency_penalty");
        body.remove("presence_penalty");
        if (body.has("stop")) {
            JsonNode stop = body.remove("stop");
            if (stop != null && stop.isArray() && stop.size() > 0) {
                body.set("stop_sequences", stop);
            }
        }
        return body;
    }

    /** Compute a {@code <base_url><endpoint>} URL ensuring no double slashes. */
    static String messages_endpoint(String base_url, String endpoint) {
        String trimmed = base_url;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Allow the caller to pass either a fully-qualified endpoint url or a
        // bare base url; prefer the explicit endpoint over duplicate paths.
        if (trimmed.endsWith(endpoint)) {
            return trimmed;
        }
        return trimmed + endpoint;
    }

    /**
     * Compute the binary backoff for {@code attempt}. Saturates at
     * {@link AnthropicConfig#max_backoff()} when the multiplier overflows.
     */
    Duration backoff_for_attempt(int attempt) {
        int shift = Math.max(0, attempt - 1);
        if (shift >= 31) {
            return config.max_backoff();
        }
        long multiplier = 1L << shift;
        long delay_nanos;
        try {
            delay_nanos = Math.multiplyExact(config.initial_backoff().toNanos(), multiplier);
        } catch (ArithmeticException overflow) {
            return config.max_backoff();
        }
        Duration candidate = Duration.ofNanos(delay_nanos);
        return candidate.compareTo(config.max_backoff()) > 0 ? config.max_backoff() : candidate;
    }

    private void sleep_for_jittered_backoff(int attempt) {
        Duration base = backoff_for_attempt(attempt);
        long base_nanos = base.toNanos();
        long jitter_nanos = jitter_for_base_nanos(base_nanos);
        long total = base_nanos + jitter_nanos;
        try {
            Thread.sleep(Duration.ofNanos(total));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw AnthropicException.io("backoff interrupted", error);
        }
    }

    static long jitter_for_base_nanos(long base_nanos) {
        if (base_nanos == 0) {
            return 0;
        }
        long now = System.nanoTime();
        long mixed = now + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        long bound = base_nanos + 1;
        return Math.floorMod(mixed, bound);
    }

    private static Optional<String> request_id_from_headers(HttpHeaders headers) {
        return headers.firstValue(REQUEST_ID_HEADER).or(() -> headers.firstValue(ALT_REQUEST_ID_HEADER));
    }

    private static Optional<AnthropicException> expect_success_string(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Optional.empty();
        }
        Optional<String> request_id = request_id_from_headers(response.headers());
        String body = response.body() == null ? "" : response.body();
        return Optional.of(parse_error_envelope(status, body, request_id.orElse(null)));
    }

    private static Optional<AnthropicException> expect_success_stream(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Optional.empty();
        }
        Optional<String> request_id = request_id_from_headers(response.headers());
        String body;
        try (InputStream stream = response.body()) {
            body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            body = "";
        }
        return Optional.of(parse_error_envelope(status, body, request_id.orElse(null)));
    }

    static AnthropicException parse_error_envelope(int status, String body, String request_id) {
        String error_type = null;
        String message = null;
        if (!body.isEmpty()) {
            try {
                JsonNode parsed = MAPPER.readTree(body);
                JsonNode error = parsed.get("error");
                if (error != null) {
                    JsonNode typeNode = error.get("type");
                    if (typeNode != null && typeNode.isTextual()) {
                        error_type = typeNode.asText();
                    }
                    JsonNode messageNode = error.get("message");
                    if (messageNode != null && messageNode.isTextual()) {
                        message = messageNode.asText();
                    }
                }
            } catch (IOException ignored) {
                // body is not JSON
            }
        }
        boolean retryable = is_retryable_status(status);
        return AnthropicException.api(status, error_type, message, request_id, body, retryable);
    }

    /**
     * Whether {@code status} is one the Anthropic Rust client retries on.
     * Mirrors the Rust {@code is_retryable_status}: 408, 409, 429, and any
     * 5xx in the {500, 502, 503, 504} set.
     */
    static boolean is_retryable_status(int status) {
        return status == 408
                || status == 409
                || status == 429
                || status == 500
                || status == 502
                || status == 503
                || status == 504;
    }

    /** Whether this client carries credentials (api key or bearer token). */
    public boolean has_credentials() {
        return config.api_key() != null || config.auth_token() != null;
    }

    /**
     * Whether the env carries Anthropic credentials. Mirrors the Rust
     * {@code has_auth_from_env_or_saved} guard (env-only check). This is the
     * helper {@code Providers.anthropic_has_auth} should consult once it
     * needs more than the env-only fallback.
     */
    public static boolean has_auth_from_env_or_saved() {
        return read_env_non_empty("ANTHROPIC_API_KEY").isPresent()
                || read_env_non_empty("ANTHROPIC_AUTH_TOKEN").isPresent()
                || OAuthCredentials.load_access_token().isPresent();
    }

    /**
     * Read an environment variable returning {@link Optional#empty()} for
     * absent or empty values.
     */
    static Optional<String> read_env_non_empty(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * Resolve an Anthropic config from the standard env vars
     * ({@code ANTHROPIC_API_KEY}, {@code ANTHROPIC_AUTH_TOKEN},
     * {@code ANTHROPIC_BASE_URL}) and saved OAuth credentials, throwing
     * {@link AnthropicException} when no credential is found.
     */
    public static AnthropicConfig config_from_env() {
        Optional<String> api_key = read_env_non_empty("ANTHROPIC_API_KEY");
        Optional<String> auth_token =
                read_env_non_empty("ANTHROPIC_AUTH_TOKEN").or(OAuthCredentials::load_access_token);
        if (api_key.isEmpty() && auth_token.isEmpty()) {
            throw AnthropicException.missing_credentials();
        }
        Optional<String> base_url_override = read_env_non_empty("ANTHROPIC_BASE_URL");
        AnthropicConfig config = AnthropicConfig.defaults();
        if (api_key.isPresent()) {
            config = config.with_api_key(api_key.get());
        }
        if (auth_token.isPresent()) {
            config = config.with_auth_token(auth_token.get());
        }
        if (base_url_override.isPresent()) {
            config = config.with_base_url(base_url_override.get());
        }
        return config;
    }
}
