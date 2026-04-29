package org.jclaude.api.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.types.BlockDelta;
import org.jclaude.api.types.InputContentBlock;
import org.jclaude.api.types.InputMessage;
import org.jclaude.api.types.MessageRequest;
import org.jclaude.api.types.MessageResponse;
import org.jclaude.api.types.OutputContentBlock;
import org.jclaude.api.types.StreamEvent;
import org.jclaude.api.types.ToolChoice;
import org.jclaude.api.types.ToolDefinition;
import org.jclaude.api.types.ToolResultContentBlock;
import org.jclaude.api.types.Usage;

/**
 * OpenAI-compatible HTTP client used by xAI, OpenAI, DashScope, Ollama, LM
 * Studio, OpenRouter, and any other provider that exposes the OpenAI
 * {@code /chat/completions} REST shape.
 *
 * <p>Java port of {@code crates/api/src/providers/openai_compat.rs}. The
 * {@link #send_message(MessageRequest)} entry point does a non-streaming POST
 * and returns a fully translated {@link MessageResponse}.
 * {@link #stream_message(MessageRequest)} returns an iterator of
 * {@link StreamEvent}s; the upstream provider's chat-completion chunks are
 * translated to the Anthropic streaming protocol on the fly.
 */
public final class OpenAiCompatClient {

    public static final String DEFAULT_XAI_BASE_URL = "https://api.x.ai/v1";
    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private static final String REQUEST_ID_HEADER = "request-id";
    private static final String ALT_REQUEST_ID_HEADER = "x-request-id";
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(128);
    private static final int DEFAULT_MAX_RETRIES = 8;
    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private final HttpClient http;
    private final String api_key;
    private final OpenAiCompatConfig config;
    private final String base_url;
    private final int max_retries;
    private final Duration initial_backoff;
    private final Duration max_backoff;

    public OpenAiCompatClient(String api_key, OpenAiCompatConfig config) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                api_key,
                config,
                read_base_url(config),
                DEFAULT_MAX_RETRIES,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF);
    }

    private OpenAiCompatClient(
            HttpClient http,
            String api_key,
            OpenAiCompatConfig config,
            String base_url,
            int max_retries,
            Duration initial_backoff,
            Duration max_backoff) {
        this.http = http;
        this.api_key = api_key;
        this.config = config;
        this.base_url = base_url;
        this.max_retries = max_retries;
        this.initial_backoff = initial_backoff;
        this.max_backoff = max_backoff;
    }

    /** Resolve a credential from the env (or a {@code .env} file) and build a client. */
    public static OpenAiCompatClient from_env(OpenAiCompatConfig config) {
        Optional<String> api_key = Providers.read_env_non_empty(config.api_key_env());
        if (api_key.isEmpty()) {
            throw OpenAiCompatException.missing_credentials(config.provider_name(), config.credential_env_vars());
        }
        return new OpenAiCompatClient(api_key.get(), config);
    }

    /** Whether the env (or a {@code .env} file) carries a non-empty API key. */
    public static boolean has_api_key(String key) {
        return Providers.has_api_key(key);
    }

    /** Effective base URL — env override or the registered default. */
    public static String read_base_url(OpenAiCompatConfig config) {
        Optional<String> override = Providers.read_env_non_empty(config.base_url_env());
        return override.orElse(config.default_base_url());
    }

    public String base_url() {
        return base_url;
    }

    public OpenAiCompatConfig config() {
        return config;
    }

    /** Returns a new client pointed at {@code newBaseUrl}. */
    public OpenAiCompatClient with_base_url(String newBaseUrl) {
        return new OpenAiCompatClient(http, api_key, config, newBaseUrl, max_retries, initial_backoff, max_backoff);
    }

    /** Returns a new client with an alternate retry policy. */
    public OpenAiCompatClient with_retry_policy(int max_retries, Duration initial_backoff, Duration max_backoff) {
        return new OpenAiCompatClient(http, api_key, config, base_url, max_retries, initial_backoff, max_backoff);
    }

    /** Override the underlying {@link HttpClient} (used by integration tests). */
    public OpenAiCompatClient with_http_client(HttpClient client) {
        return new OpenAiCompatClient(client, api_key, config, base_url, max_retries, initial_backoff, max_backoff);
    }

    /** Send a non-streaming chat-completion request and return the translated response. */
    public MessageResponse send_message(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        MessageRequest non_streaming = with_streaming_disabled(request);
        Optional<Providers.ContextWindowExceededError> overflow = Providers.preflight_message_request(non_streaming);
        if (overflow.isPresent()) {
            throw OpenAiCompatException.context_window_exceeded(overflow.get());
        }
        HttpResponse<String> response = send_with_retry_string(non_streaming);
        Optional<String> request_id = request_id_from_headers(response.headers());
        String body = response.body() == null ? "" : response.body();
        // Some backends embed an error envelope in a 200 response. Surface the
        // upstream message rather than letting deserialization fail with a
        // cryptic "missing field" parse error.
        Optional<OpenAiCompatException> embedded = parse_embedded_error(body, request_id.orElse(null));
        if (embedded.isPresent()) {
            throw embedded.get();
        }
        try {
            JsonNode payload = MAPPER.readTree(body);
            MessageResponse normalized = normalize_response(non_streaming.model(), payload);
            return request_id.isPresent() && normalized.request_id() == null
                    ? new MessageResponse(
                            normalized.id(),
                            normalized.kind(),
                            normalized.role(),
                            normalized.content(),
                            normalized.model(),
                            normalized.stop_reason(),
                            normalized.stop_sequence(),
                            normalized.usage(),
                            request_id.get())
                    : normalized;
        } catch (IOException error) {
            throw OpenAiCompatException.json_deserialize(config.provider_name(), non_streaming.model(), body, error);
        }
    }

    /** Send a streaming chat-completion request and return an iterator of translated stream events. */
    public StreamingResponse stream_message(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Providers.ContextWindowExceededError> overflow = Providers.preflight_message_request(request);
        if (overflow.isPresent()) {
            throw OpenAiCompatException.context_window_exceeded(overflow.get());
        }
        MessageRequest streaming = request.with_streaming();
        HttpResponse<InputStream> response = send_with_retry_stream(streaming);
        Optional<String> request_id = request_id_from_headers(response.headers());
        return new StreamingResponse(
                request_id.orElse(null),
                new StreamEventIterator(response.body(), config.provider_name(), streaming.model()));
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
     * Iterator that consumes a SSE-formatted {@link InputStream} and yields the
     * translated Anthropic-shaped {@link StreamEvent}s. Mirrors the Rust
     * {@code MessageStream}.
     */
    public static final class StreamEventIterator implements Iterator<StreamEvent>, AutoCloseable {

        private final InputStream input;
        private final String provider;
        private final String model;
        private final ChunkParser parser;
        private final StreamState state;
        private final Deque<StreamEvent> pending = new ArrayDeque<>();
        private boolean done;
        private boolean closed;
        private byte[] readBuffer = new byte[8192];

        public StreamEventIterator(InputStream input, String provider, String model) {
            this.input = input;
            this.provider = provider;
            this.model = model;
            this.parser = new ChunkParser();
            this.state = new StreamState(model);
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
            return pending.removeFirst();
        }

        private void ensure_pending() {
            while (pending.isEmpty()) {
                if (done) {
                    pending.addAll(state.finish());
                    return;
                }
                int read;
                try {
                    read = input.read(readBuffer);
                } catch (IOException error) {
                    throw OpenAiCompatException.io("failed to read stream chunk", error);
                }
                if (read < 0) {
                    done = true;
                    pending.addAll(state.finish());
                    return;
                }
                if (read == 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(readBuffer, 0, chunk, 0, read);
                List<JsonNode> chunks = parser.push(chunk, provider, model);
                for (JsonNode jsonChunk : chunks) {
                    pending.addAll(state.ingest_chunk(jsonChunk));
                }
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

    private MessageRequest with_streaming_disabled(MessageRequest request) {
        if (!request.stream()) {
            return request;
        }
        return new MessageRequest(
                request.model(),
                request.max_tokens(),
                request.messages(),
                request.system(),
                request.tools(),
                request.tool_choice(),
                false,
                request.temperature(),
                request.top_p(),
                request.frequency_penalty(),
                request.presence_penalty(),
                request.stop(),
                request.reasoning_effort());
    }

    private HttpResponse<String> send_with_retry_string(MessageRequest request) {
        int attempts = 0;
        OpenAiCompatException last_error;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> response = send_raw_request_string(request);
                Optional<OpenAiCompatException> failure = expect_success_string(response);
                if (failure.isEmpty()) {
                    return response;
                }
                if (failure.get().is_retryable() && attempts <= max_retries + 1) {
                    last_error = failure.get();
                } else {
                    throw failure.get();
                }
            } catch (OpenAiCompatException error) {
                if (error.is_retryable() && attempts <= max_retries + 1) {
                    last_error = error;
                } else {
                    throw error;
                }
            }
            if (attempts > max_retries) {
                throw OpenAiCompatException.retries_exhausted(attempts, last_error);
            }
            sleep_for_jittered_backoff(attempts);
        }
    }

    private HttpResponse<InputStream> send_with_retry_stream(MessageRequest request) {
        int attempts = 0;
        OpenAiCompatException last_error;
        while (true) {
            attempts++;
            try {
                HttpResponse<InputStream> response = send_raw_request_stream(request);
                Optional<OpenAiCompatException> failure = expect_success_stream(response);
                if (failure.isEmpty()) {
                    return response;
                }
                if (failure.get().is_retryable() && attempts <= max_retries + 1) {
                    last_error = failure.get();
                } else {
                    throw failure.get();
                }
            } catch (OpenAiCompatException error) {
                if (error.is_retryable() && attempts <= max_retries + 1) {
                    last_error = error;
                } else {
                    throw error;
                }
            }
            if (attempts > max_retries) {
                throw OpenAiCompatException.retries_exhausted(attempts, last_error);
            }
            sleep_for_jittered_backoff(attempts);
        }
    }

    private HttpResponse<String> send_raw_request_string(MessageRequest request) {
        check_request_body_size(request, config);
        HttpRequest http_request = build_http_request(request);
        try {
            return http.send(http_request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw OpenAiCompatException.io("failed to dispatch request to " + http_request.uri(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw OpenAiCompatException.io("request interrupted", error);
        }
    }

    private HttpResponse<InputStream> send_raw_request_stream(MessageRequest request) {
        check_request_body_size(request, config);
        HttpRequest http_request = build_http_request(request);
        try {
            return http.send(http_request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw OpenAiCompatException.io("failed to dispatch request to " + http_request.uri(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw OpenAiCompatException.io("request interrupted", error);
        }
    }

    private HttpRequest build_http_request(MessageRequest request) {
        URI uri = URI.create(chat_completions_endpoint(base_url));
        JsonNode payload = build_chat_completion_request(request, config);
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (IOException error) {
            throw OpenAiCompatException.io("failed to serialize request payload", error);
        }
        return HttpRequest.newBuilder(uri)
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + api_key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    /**
     * Compute the binary backoff for {@code attempt}. Saturates at
     * {@link #max_backoff} when the multiplier overflows (matches Rust).
     */
    Duration backoff_for_attempt(int attempt) {
        int shift = Math.max(0, attempt - 1);
        if (shift >= 31) {
            return max_backoff;
        }
        long multiplier = 1L << shift;
        long delay_nanos;
        try {
            delay_nanos = Math.multiplyExact(initial_backoff.toNanos(), multiplier);
        } catch (ArithmeticException overflow) {
            return max_backoff;
        }
        Duration candidate = Duration.ofNanos(delay_nanos);
        return candidate.compareTo(max_backoff) > 0 ? max_backoff : candidate;
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
            throw OpenAiCompatException.io("backoff interrupted", error);
        }
    }

    private static long jitter_for_base_nanos(long base_nanos) {
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

    /** Estimate the serialized JSON request body size in bytes. */
    public static int estimate_request_body_size(MessageRequest request, OpenAiCompatConfig config) {
        JsonNode payload = build_chat_completion_request(request, config);
        try {
            return MAPPER.writeValueAsBytes(payload).length;
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * Pre-flight check for request body size against provider limits. Throws a
     * {@link OpenAiCompatException} with kind {@code REQUEST_BODY_SIZE_EXCEEDED}
     * when the request is too large.
     */
    public static void check_request_body_size(MessageRequest request, OpenAiCompatConfig config) {
        int estimated_bytes = estimate_request_body_size(request, config);
        int max_bytes = config.max_request_body_bytes();
        if (estimated_bytes > max_bytes) {
            throw OpenAiCompatException.request_body_size_exceeded(estimated_bytes, max_bytes, config.provider_name());
        }
    }

    /** Build the OpenAI chat-completion request payload. */
    public static JsonNode build_chat_completion_request(MessageRequest request, OpenAiCompatConfig config) {
        ArrayNode messages = MAPPER.createArrayNode();
        if (request.system() != null && !request.system().isEmpty()) {
            ObjectNode system = MAPPER.createObjectNode();
            system.put("role", "system");
            system.put("content", request.system());
            messages.add(system);
        }
        String wire_model = strip_routing_prefix(request.model());
        for (InputMessage message : request.messages()) {
            for (JsonNode translated : translate_message(message, wire_model)) {
                messages.add(translated);
            }
        }
        messages = sanitize_tool_message_pairing(messages);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", wire_model);
        // gpt-5* requires `max_completion_tokens`; older models accept both.
        if (wire_model.startsWith("gpt-5")) {
            payload.put("max_completion_tokens", request.max_tokens());
        } else {
            payload.put("max_tokens", request.max_tokens());
        }
        payload.set("messages", messages);
        payload.put("stream", request.stream());

        if (request.stream() && should_request_stream_usage(config)) {
            ObjectNode stream_options = MAPPER.createObjectNode();
            stream_options.put("include_usage", true);
            payload.set("stream_options", stream_options);
        }

        if (request.tools() != null) {
            ArrayNode tools = MAPPER.createArrayNode();
            for (ToolDefinition tool : request.tools()) {
                tools.add(openai_tool_definition(tool));
            }
            payload.set("tools", tools);
        }
        if (request.tool_choice() != null) {
            payload.set("tool_choice", openai_tool_choice(request.tool_choice()));
        }

        // Reasoning models (o1/o3/o4/grok-3-mini/qwen-qwq/qwen3-thinking)
        // reject tuning params with 400; silently strip them.
        if (!is_reasoning_model(request.model())) {
            if (request.temperature() != null) {
                payload.put("temperature", request.temperature());
            }
            if (request.top_p() != null) {
                payload.put("top_p", request.top_p());
            }
            if (request.frequency_penalty() != null) {
                payload.put("frequency_penalty", request.frequency_penalty());
            }
            if (request.presence_penalty() != null) {
                payload.put("presence_penalty", request.presence_penalty());
            }
        }
        if (request.stop() != null && !request.stop().isEmpty()) {
            ArrayNode stops = MAPPER.createArrayNode();
            for (String s : request.stop()) {
                stops.add(s);
            }
            payload.set("stop", stops);
        }
        if (request.reasoning_effort() != null) {
            payload.put("reasoning_effort", request.reasoning_effort());
        }
        return payload;
    }

    /**
     * Strip a known routing prefix (e.g. {@code "openai/"}) from {@code model}
     * so the wire model id matches what the upstream provider expects.
     */
    public static String strip_routing_prefix(String model) {
        int slash = model.indexOf('/');
        if (slash < 0) {
            return model;
        }
        String prefix = model.substring(0, slash);
        return switch (prefix) {
            case "openai", "xai", "grok", "qwen", "kimi" -> model.substring(slash + 1);
            default -> model;
        };
    }

    /**
     * Whether {@code model} is a reasoning model that rejects sampling tuning
     * parameters.
     */
    public static boolean is_reasoning_model(String model) {
        String lowered = model.toLowerCase(Locale.ROOT);
        int slash = lowered.lastIndexOf('/');
        String canonical = slash < 0 ? lowered : lowered.substring(slash + 1);
        return canonical.startsWith("o1")
                || canonical.startsWith("o3")
                || canonical.startsWith("o4")
                || canonical.equals("grok-3-mini")
                || canonical.startsWith("qwen-qwq")
                || canonical.startsWith("qwq")
                || canonical.contains("thinking");
    }

    /**
     * Whether {@code model} rejects the {@code is_error} field in tool result
     * messages — kimi family models reject it with HTTP 400.
     */
    public static boolean model_rejects_is_error_field(String model) {
        String lowered = model.toLowerCase(Locale.ROOT);
        int slash = lowered.lastIndexOf('/');
        String canonical = slash < 0 ? lowered : lowered.substring(slash + 1);
        return canonical.startsWith("kimi");
    }

    /** Translate an Anthropic-shaped {@link InputMessage} to one or more OpenAI messages. */
    public static List<JsonNode> translate_message(InputMessage message, String model) {
        boolean supports_is_error = !model_rejects_is_error_field(model);
        if ("assistant".equals(message.role())) {
            StringBuilder text = new StringBuilder();
            ArrayNode tool_calls = MAPPER.createArrayNode();
            for (InputContentBlock block : message.content()) {
                if (block instanceof InputContentBlock.Text textBlock) {
                    text.append(textBlock.text());
                } else if (block instanceof InputContentBlock.ToolUse toolUse) {
                    ObjectNode call = MAPPER.createObjectNode();
                    call.put("id", toolUse.id());
                    call.put("type", "function");
                    ObjectNode function = MAPPER.createObjectNode();
                    function.put("name", toolUse.name());
                    function.put("arguments", json_input_to_string(toolUse.input()));
                    call.set("function", function);
                    tool_calls.add(call);
                }
                // ToolResult blocks on assistant messages are dropped — they live on user turns.
            }
            if (text.length() == 0 && tool_calls.size() == 0) {
                return List.of();
            }
            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("role", "assistant");
            if (text.length() > 0) {
                msg.put("content", text.toString());
            } else {
                msg.set("content", MAPPER.nullNode());
            }
            if (tool_calls.size() > 0) {
                msg.set("tool_calls", tool_calls);
            }
            return List.of(msg);
        }
        // User and system messages: text becomes role:user, tool results become role:tool.
        List<JsonNode> out = new ArrayList<>();
        for (InputContentBlock block : message.content()) {
            if (block instanceof InputContentBlock.Text text) {
                ObjectNode user = MAPPER.createObjectNode();
                user.put("role", "user");
                user.put("content", text.text());
                out.add(user);
            } else if (block instanceof InputContentBlock.ToolResult result) {
                ObjectNode tool = MAPPER.createObjectNode();
                tool.put("role", "tool");
                tool.put("tool_call_id", result.tool_use_id());
                tool.put("content", flatten_tool_result_content(result.content()));
                if (supports_is_error) {
                    tool.put("is_error", result.is_error());
                }
                out.add(tool);
            }
            // ToolUse blocks on non-assistant messages are dropped.
        }
        return out;
    }

    /**
     * Serialise the {@code input} JSON of a tool-use block to a string using
     * compact JSON (matches Rust's {@code input.to_string()}).
     */
    private static String json_input_to_string(JsonNode input) {
        if (input == null || input.isNull()) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(input);
        } catch (IOException error) {
            return "null";
        }
    }

    /**
     * Drop {@code role: "tool"} messages from {@code messages} when the
     * preceding non-tool message is an assistant turn that does not carry a
     * matching {@code tool_calls[].id}. Public for benchmarking.
     */
    public static ArrayNode sanitize_tool_message_pairing(ArrayNode messages) {
        Set<Integer> drop_indices = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonNode msg = messages.get(i);
            if (!"tool".equals(text_field(msg, "role"))) {
                continue;
            }
            String tool_call_id = text_field(msg, "tool_call_id");
            if (tool_call_id == null) {
                tool_call_id = "";
            }
            JsonNode preceding = null;
            for (int j = i - 1; j >= 0; j--) {
                JsonNode candidate = messages.get(j);
                if (!"tool".equals(text_field(candidate, "role"))) {
                    preceding = candidate;
                    break;
                }
            }
            String preceding_role = preceding == null
                    ? ""
                    : Optional.ofNullable(text_field(preceding, "role")).orElse("");
            if (!"assistant".equals(preceding_role)) {
                continue;
            }
            JsonNode tool_calls = preceding.get("tool_calls");
            boolean paired = false;
            if (tool_calls != null && tool_calls.isArray()) {
                for (JsonNode tc : tool_calls) {
                    if (Objects.equals(text_field(tc, "id"), tool_call_id)) {
                        paired = true;
                        break;
                    }
                }
            }
            if (!paired) {
                drop_indices.add(i);
            }
        }
        if (drop_indices.isEmpty()) {
            return messages;
        }
        ArrayNode filtered = MAPPER.createArrayNode();
        for (int i = 0; i < messages.size(); i++) {
            if (!drop_indices.contains(i)) {
                filtered.add(messages.get(i));
            }
        }
        return filtered;
    }

    private static String text_field(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : null;
    }

    /** Flatten an Anthropic-shaped tool result content list to a single string. */
    public static String flatten_tool_result_content(List<ToolResultContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            if (i > 0) {
                result.append('\n');
            }
            ToolResultContentBlock block = content.get(i);
            if (block instanceof ToolResultContentBlock.Text text) {
                result.append(text.text());
            } else if (block instanceof ToolResultContentBlock.Json json) {
                result.append(json.value() == null ? "null" : json.value().toString());
            }
        }
        return result.toString();
    }

    /**
     * Recursively ensure every object-type node in a JSON Schema has
     * {@code "properties"} (at least an empty object) and
     * {@code "additionalProperties": false}. Mirrors the Rust normaliser.
     */
    static void normalize_object_schema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) schema;
        JsonNode type = obj.get("type");
        if (type != null && type.isTextual() && "object".equals(type.asText())) {
            if (!obj.has("properties")) {
                obj.set("properties", MAPPER.createObjectNode());
            }
            if (!obj.has("additionalProperties")) {
                obj.put("additionalProperties", false);
            }
        }
        JsonNode properties = obj.get("properties");
        if (properties != null && properties.isObject()) {
            ObjectNode props_obj = (ObjectNode) properties;
            props_obj.fieldNames().forEachRemaining(field -> normalize_object_schema(props_obj.get(field)));
        }
        JsonNode items = obj.get("items");
        if (items != null) {
            normalize_object_schema(items);
        }
    }

    private static JsonNode openai_tool_definition(ToolDefinition tool) {
        JsonNode parameters = tool.input_schema() == null
                ? MAPPER.createObjectNode()
                : tool.input_schema().deepCopy();
        normalize_object_schema(parameters);
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("type", "function");
        ObjectNode function = MAPPER.createObjectNode();
        function.put("name", tool.name());
        if (tool.description() != null) {
            function.put("description", tool.description());
        } else {
            function.set("description", MAPPER.nullNode());
        }
        function.set("parameters", parameters);
        wrapper.set("function", function);
        return wrapper;
    }

    private static JsonNode openai_tool_choice(ToolChoice tool_choice) {
        if (tool_choice instanceof ToolChoice.Auto) {
            return TextNode.valueOf("auto");
        }
        if (tool_choice instanceof ToolChoice.Any) {
            return TextNode.valueOf("required");
        }
        if (tool_choice instanceof ToolChoice.Tool tool) {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.put("type", "function");
            ObjectNode inner = MAPPER.createObjectNode();
            inner.put("name", tool.name());
            wrapper.set("function", inner);
            return wrapper;
        }
        throw new IllegalArgumentException("unknown tool choice: " + tool_choice);
    }

    private static boolean should_request_stream_usage(OpenAiCompatConfig config) {
        return "OpenAI".equals(config.provider_name());
    }

    private MessageResponse normalize_response(String model, JsonNode payload) {
        JsonNode choices = payload.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            throw OpenAiCompatException.invalid_sse_frame("chat completion response missing choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        if (message == null) {
            throw OpenAiCompatException.invalid_sse_frame("chat completion response missing message");
        }
        List<OutputContentBlock> content = new ArrayList<>();
        JsonNode contentNode = message.get("content");
        if (contentNode != null
                && contentNode.isTextual()
                && !contentNode.asText().isEmpty()) {
            content.add(new OutputContentBlock.Text(contentNode.asText()));
        }
        JsonNode tool_calls = message.get("tool_calls");
        if (tool_calls != null && tool_calls.isArray()) {
            for (JsonNode call : tool_calls) {
                String id = text_field(call, "id");
                JsonNode function = call.get("function");
                String name = text_field(function, "name");
                String arguments = text_field(function, "arguments");
                if (arguments == null) {
                    arguments = "";
                }
                JsonNode parsed = parse_tool_arguments(arguments);
                content.add(new OutputContentBlock.ToolUse(id, name, parsed));
            }
        }
        String role = Optional.ofNullable(text_field(message, "role")).orElse("assistant");
        String response_model = Optional.ofNullable(text_field(payload, "model"))
                .filter(value -> !value.isEmpty())
                .orElse(model);
        String stop_reason = Optional.ofNullable(text_field(choice, "finish_reason"))
                .map(OpenAiCompatClient::normalize_finish_reason)
                .orElse(null);
        Usage usage = parse_usage(payload.get("usage"));
        String id = Optional.ofNullable(text_field(payload, "id")).orElse("");
        return new MessageResponse(id, "message", role, content, response_model, stop_reason, null, usage, null);
    }

    private static Usage parse_usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Usage.ZERO;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        return new Usage(prompt, 0, 0, completion);
    }

    static JsonNode parse_tool_arguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            ObjectNode raw = MAPPER.createObjectNode();
            raw.put("raw", arguments == null ? "" : arguments);
            return raw;
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (IOException ignored) {
            ObjectNode raw = MAPPER.createObjectNode();
            raw.put("raw", arguments);
            return raw;
        }
    }

    /** Compute the {@code /chat/completions} URL from a base URL. */
    public static String chat_completions_endpoint(String base_url) {
        String trimmed = base_url;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    private static Optional<String> request_id_from_headers(HttpHeaders headers) {
        return headers.firstValue(REQUEST_ID_HEADER).or(() -> headers.firstValue(ALT_REQUEST_ID_HEADER));
    }

    private static Optional<OpenAiCompatException> expect_success_string(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Optional.empty();
        }
        Optional<String> request_id = request_id_from_headers(response.headers());
        String body = response.body() == null ? "" : response.body();
        return Optional.of(parse_error_envelope(status, body, request_id.orElse(null)));
    }

    private static Optional<OpenAiCompatException> expect_success_stream(HttpResponse<InputStream> response) {
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

    private static OpenAiCompatException parse_error_envelope(int status, String body, String request_id) {
        String error_type = null;
        String message = null;
        if (!body.isEmpty()) {
            try {
                JsonNode parsed = MAPPER.readTree(body);
                JsonNode error = parsed.get("error");
                if (error != null) {
                    error_type = text_field(error, "type");
                    message = text_field(error, "message");
                }
            } catch (IOException ignored) {
                // body is not JSON; preserve raw body in the exception
            }
        }
        boolean retryable = is_retryable_status(status);
        String suggested = suggested_action_for_status(status);
        return OpenAiCompatException.api(status, error_type, message, request_id, body, retryable, suggested);
    }

    private static Optional<OpenAiCompatException> parse_embedded_error(String body, String request_id) {
        if (body.isEmpty()) {
            return Optional.empty();
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(body);
        } catch (IOException ignored) {
            return Optional.empty();
        }
        JsonNode error = parsed.get("error");
        if (error == null) {
            return Optional.empty();
        }
        String message = Optional.ofNullable(text_field(error, "message")).orElse("provider returned an error");
        String error_type = text_field(error, "type");
        Integer code = null;
        JsonNode codeNode = error.get("code");
        if (codeNode != null && codeNode.isNumber()) {
            code = codeNode.asInt();
        }
        int status = code == null ? 400 : code;
        if (status < 100 || status > 599) {
            status = 400;
        }
        return Optional.of(OpenAiCompatException.api(
                status, error_type, message, request_id, body, false, suggested_action_for_status(status)));
    }

    private static boolean is_retryable_status(int status) {
        return status == 408 || status == 409 || status == 429 || (status >= 500 && status <= 504);
    }

    private static String suggested_action_for_status(int status) {
        return switch (status) {
            case 401 -> "Check API key is set correctly and has not expired";
            case 403 -> "Verify API key has required permissions for this operation";
            case 413 -> "Reduce prompt size or context window before retrying";
            case 429 -> "Wait a moment before retrying; consider reducing request rate";
            case 500 -> "Provider server error - retry after a brief wait";
            case 502, 503, 504 -> "Provider gateway error - retry after a brief wait";
            default -> null;
        };
    }

    /** Map an OpenAI {@code finish_reason} to the Anthropic {@code stop_reason} vocabulary. */
    public static String normalize_finish_reason(String value) {
        return switch (value) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            default -> value;
        };
    }

    /**
     * Buffered SSE chunk parser used by the streaming iterator. Splits the
     * byte stream on {@code \n\n} (or {@code \r\n\r\n}) and decodes each
     * frame as a {@code data:} JSON payload.
     */
    static final class ChunkParser {

        private final ByteBufferQueue buffer = new ByteBufferQueue();

        List<JsonNode> push(byte[] chunk, String provider, String model) {
            buffer.append(chunk);
            List<JsonNode> events = new ArrayList<>();
            String frame;
            while ((frame = buffer.next_frame()) != null) {
                Optional<JsonNode> parsed = parse_sse_frame(frame, provider, model);
                parsed.ifPresent(events::add);
            }
            return events;
        }
    }

    static Optional<JsonNode> parse_sse_frame(String frame, String provider, String model) {
        String trimmed = frame.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        List<String> data_lines = new ArrayList<>();
        for (String line : trimmed.split("\n", -1)) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (stripped.startsWith(":")) {
                continue;
            }
            if (stripped.startsWith("data:")) {
                String value = stripped.substring("data:".length());
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                data_lines.add(value);
            }
        }
        if (data_lines.isEmpty()) {
            return Optional.empty();
        }
        String payload = String.join("\n", data_lines);
        if ("[DONE]".equals(payload)) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = MAPPER.readTree(payload);
            JsonNode error = parsed.get("error");
            if (error != null) {
                String message = Optional.ofNullable(text_field(error, "message"))
                        .orElse("provider returned an error in stream");
                String error_type = text_field(error, "type");
                Integer code = null;
                JsonNode codeNode = error.get("code");
                if (codeNode != null && codeNode.isNumber()) {
                    code = codeNode.asInt();
                }
                int status = code == null ? 400 : code;
                if (status < 100 || status > 599) {
                    status = 400;
                }
                throw OpenAiCompatException.api(
                        status, error_type, message, null, payload, false, suggested_action_for_status(status));
            }
            return Optional.of(parsed);
        } catch (IOException error) {
            throw OpenAiCompatException.json_deserialize(provider, model, payload, error);
        }
    }

    /**
     * Stateful translator that converts OpenAI chat-completion chunks into
     * Anthropic {@link StreamEvent}s. Mirrors the Rust {@code StreamState}.
     */
    static final class StreamState {

        private final String model;
        private boolean message_started;
        private boolean text_started;
        private boolean text_finished;
        private boolean finished;
        private String stop_reason;
        private Usage usage;
        private final TreeMap<Integer, ToolCallState> tool_calls = new TreeMap<>();

        StreamState(String model) {
            this.model = model;
        }

        List<StreamEvent> ingest_chunk(JsonNode chunk) {
            List<StreamEvent> events = new ArrayList<>();
            if (!message_started) {
                message_started = true;
                String id = Optional.ofNullable(text_field(chunk, "id")).orElse("");
                String chunk_model =
                        Optional.ofNullable(text_field(chunk, "model")).orElse(model);
                events.add(new StreamEvent.MessageStart(new MessageResponse(
                        id, "message", "assistant", new ArrayList<>(), chunk_model, null, null, Usage.ZERO, null)));
            }
            JsonNode usageNode = chunk.get("usage");
            if (usageNode != null && usageNode.isObject()) {
                long prompt = usageNode.path("prompt_tokens").asLong(0);
                long completion = usageNode.path("completion_tokens").asLong(0);
                this.usage = new Usage(prompt, 0, 0, completion);
            }

            JsonNode choices = chunk.get("choices");
            if (choices == null || !choices.isArray()) {
                return events;
            }
            for (JsonNode choice : choices) {
                JsonNode delta = choice.get("delta");
                if (delta == null) {
                    delta = MAPPER.createObjectNode();
                }
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && contentNode.isTextual()) {
                    String contentText = contentNode.asText();
                    if (!contentText.isEmpty()) {
                        if (!text_started) {
                            text_started = true;
                            events.add(new StreamEvent.ContentBlockStart(0, new OutputContentBlock.Text("")));
                        }
                        events.add(new StreamEvent.ContentBlockDelta(0, new BlockDelta.TextDelta(contentText)));
                    }
                }

                JsonNode tool_calls_node = delta.get("tool_calls");
                if (tool_calls_node != null && tool_calls_node.isArray()) {
                    String finish_reason = text_field(choice, "finish_reason");
                    for (JsonNode call : tool_calls_node) {
                        int index = call.path("index").asInt(0);
                        ToolCallState state = tool_calls.computeIfAbsent(index, k -> new ToolCallState());
                        state.apply(call);
                        if (!state.started) {
                            Optional<StreamEvent.ContentBlockStart> start_event = state.start_event();
                            if (start_event.isEmpty()) {
                                continue;
                            }
                            state.started = true;
                            events.add(start_event.get());
                        }
                        Optional<StreamEvent.ContentBlockDelta> delta_event = state.delta_event();
                        delta_event.ifPresent(events::add);
                        if ("tool_calls".equals(finish_reason) && !state.stopped) {
                            state.stopped = true;
                            events.add(new StreamEvent.ContentBlockStop(state.block_index()));
                        }
                    }
                }

                String finish_reason = text_field(choice, "finish_reason");
                if (finish_reason != null) {
                    this.stop_reason = normalize_finish_reason(finish_reason);
                    if ("tool_calls".equals(finish_reason)) {
                        for (ToolCallState state : tool_calls.values()) {
                            if (state.started && !state.stopped) {
                                state.stopped = true;
                                events.add(new StreamEvent.ContentBlockStop(state.block_index()));
                            }
                        }
                    }
                }
            }
            return events;
        }

        List<StreamEvent> finish() {
            if (finished) {
                return List.of();
            }
            finished = true;
            List<StreamEvent> events = new ArrayList<>();
            if (text_started && !text_finished) {
                text_finished = true;
                events.add(new StreamEvent.ContentBlockStop(0));
            }
            for (ToolCallState state : tool_calls.values()) {
                if (!state.started) {
                    Optional<StreamEvent.ContentBlockStart> start_event = state.start_event();
                    if (start_event.isPresent()) {
                        state.started = true;
                        events.add(start_event.get());
                        Optional<StreamEvent.ContentBlockDelta> delta_event = state.delta_event();
                        delta_event.ifPresent(events::add);
                    }
                }
                if (state.started && !state.stopped) {
                    state.stopped = true;
                    events.add(new StreamEvent.ContentBlockStop(state.block_index()));
                }
            }
            if (message_started) {
                Usage final_usage = usage == null ? Usage.ZERO : usage;
                events.add(new StreamEvent.MessageDelta(
                        new StreamEvent.MessageDelta.Delta(stop_reason == null ? "end_turn" : stop_reason, null),
                        final_usage));
                events.add(new StreamEvent.MessageStop());
            }
            return events;
        }
    }

    static final class ToolCallState {

        int openai_index;
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
        int emitted_len;
        boolean started;
        boolean stopped;

        void apply(JsonNode tool_call) {
            this.openai_index = tool_call.path("index").asInt(0);
            String id_field = text_field(tool_call, "id");
            if (id_field != null) {
                this.id = id_field;
            }
            JsonNode function = tool_call.get("function");
            if (function != null) {
                String name_field = text_field(function, "name");
                if (name_field != null) {
                    this.name = name_field;
                }
                String args_field = text_field(function, "arguments");
                if (args_field != null) {
                    this.arguments.append(args_field);
                }
            }
        }

        int block_index() {
            return openai_index + 1;
        }

        Optional<StreamEvent.ContentBlockStart> start_event() {
            if (name == null) {
                return Optional.empty();
            }
            String use_id = id == null ? "tool_call_" + openai_index : id;
            return Optional.of(new StreamEvent.ContentBlockStart(
                    block_index(), new OutputContentBlock.ToolUse(use_id, name, MAPPER.createObjectNode())));
        }

        Optional<StreamEvent.ContentBlockDelta> delta_event() {
            if (emitted_len >= arguments.length()) {
                return Optional.empty();
            }
            String chunk = arguments.substring(emitted_len);
            emitted_len = arguments.length();
            return Optional.of(new StreamEvent.ContentBlockDelta(block_index(), new BlockDelta.InputJsonDelta(chunk)));
        }
    }

    /**
     * Simple growable byte buffer with a {@link #next_frame()} helper that
     * extracts the next SSE frame on a {@code \n\n} (or {@code \r\n\r\n})
     * boundary. Used by {@link ChunkParser}.
     */
    static final class ByteBufferQueue {

        private byte[] data = new byte[0];

        void append(byte[] chunk) {
            byte[] joined = new byte[data.length + chunk.length];
            System.arraycopy(data, 0, joined, 0, data.length);
            System.arraycopy(chunk, 0, joined, data.length, chunk.length);
            data = joined;
        }

        String next_frame() {
            int separator_position = -1;
            int separator_length = 0;
            int lf_index = index_of(data, new byte[] {'\n', '\n'});
            int crlf_index = index_of(data, new byte[] {'\r', '\n', '\r', '\n'});
            if (lf_index >= 0 && (crlf_index < 0 || lf_index <= crlf_index)) {
                separator_position = lf_index;
                separator_length = 2;
            } else if (crlf_index >= 0) {
                separator_position = crlf_index;
                separator_length = 4;
            }
            if (separator_position < 0) {
                return null;
            }
            String frame = new String(data, 0, separator_position, StandardCharsets.UTF_8);
            int total_consumed = separator_position + separator_length;
            byte[] remainder = new byte[data.length - total_consumed];
            System.arraycopy(data, total_consumed, remainder, 0, remainder.length);
            data = remainder;
            return frame;
        }

        private static int index_of(byte[] haystack, byte[] needle) {
            if (needle.length == 0 || haystack.length < needle.length) {
                return -1;
            }
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    /** Static helper exposed for testing. */
    static Map<Integer, ToolCallState> _testing_tool_calls(StreamState state) {
        return state.tool_calls;
    }
}
