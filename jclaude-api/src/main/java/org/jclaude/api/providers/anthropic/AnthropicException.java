package org.jclaude.api.providers.anthropic;

import java.util.Optional;

/**
 * Exception thrown by {@link AnthropicClient} when the upstream provider
 * returns a non-success HTTP status, when the body is malformed JSON, or
 * when a stream frame carries an embedded error envelope. Models the Rust
 * {@code ApiError} variants relevant to the Anthropic native client.
 *
 * <p>The {@link Kind} enum covers the API-error classes documented by
 * Anthropic: {@code invalid_request_error}, {@code authentication_error},
 * {@code permission_error}, {@code not_found_error}, {@code request_too_large},
 * {@code rate_limit_error}, {@code api_error}, {@code overloaded_error}.
 */
public final class AnthropicException extends RuntimeException {

    public enum Kind {
        API,
        MISSING_CREDENTIALS,
        REQUEST_BODY_SIZE_EXCEEDED,
        CONTEXT_WINDOW_EXCEEDED,
        JSON_DESERIALIZE,
        IO,
        INVALID_SSE_FRAME,
        RETRIES_EXHAUSTED
    }

    /** Error class as reported in the Anthropic JSON envelope. */
    public enum ErrorClass {
        INVALID_REQUEST_ERROR("invalid_request_error"),
        AUTHENTICATION_ERROR("authentication_error"),
        PERMISSION_ERROR("permission_error"),
        NOT_FOUND_ERROR("not_found_error"),
        REQUEST_TOO_LARGE("request_too_large"),
        RATE_LIMIT_ERROR("rate_limit_error"),
        API_ERROR("api_error"),
        OVERLOADED_ERROR("overloaded_error");

        private final String wire_name;

        ErrorClass(String wire_name) {
            this.wire_name = wire_name;
        }

        public String wire_name() {
            return wire_name;
        }

        public static Optional<ErrorClass> from_wire(String wire) {
            if (wire == null) {
                return Optional.empty();
            }
            for (ErrorClass kind : values()) {
                if (kind.wire_name.equals(wire)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private final Kind kind;
    private final Integer status;
    private final String error_type;
    private final ErrorClass error_class;
    private final String request_id;
    private final String body;
    private final boolean retryable;

    private AnthropicException(
            Kind kind,
            String message,
            Integer status,
            String error_type,
            String request_id,
            String body,
            boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.error_type = error_type;
        this.error_class = ErrorClass.from_wire(error_type).orElse(null);
        this.request_id = request_id;
        this.body = body;
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    public Optional<Integer> status() {
        return Optional.ofNullable(status);
    }

    public Optional<String> error_type() {
        return Optional.ofNullable(error_type);
    }

    public Optional<ErrorClass> error_class() {
        return Optional.ofNullable(error_class);
    }

    public Optional<String> request_id() {
        return Optional.ofNullable(request_id);
    }

    public Optional<String> body() {
        return Optional.ofNullable(body);
    }

    public boolean is_retryable() {
        return retryable;
    }

    public static AnthropicException missing_credentials() {
        return new AnthropicException(
                Kind.MISSING_CREDENTIALS,
                "missing Anthropic credentials; set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN",
                null,
                null,
                null,
                null,
                false,
                null);
    }

    public static AnthropicException io(String message, Throwable cause) {
        return new AnthropicException(Kind.IO, message, null, null, null, null, true, cause);
    }

    public static AnthropicException invalid_sse_frame(String message) {
        return new AnthropicException(Kind.INVALID_SSE_FRAME, message, null, null, null, null, false, null);
    }

    public static AnthropicException json_deserialize(String model, String body, Throwable cause) {
        return new AnthropicException(
                Kind.JSON_DESERIALIZE,
                "failed to deserialize Anthropic response for model " + model + ": " + cause.getMessage(),
                null,
                null,
                null,
                body,
                false,
                cause);
    }

    public static AnthropicException retries_exhausted(int attempts, AnthropicException last) {
        return new AnthropicException(
                Kind.RETRIES_EXHAUSTED,
                "retries exhausted after " + attempts + " attempts: " + last.getMessage(),
                last.status,
                last.error_type,
                last.request_id,
                last.body,
                false,
                last);
    }

    public static AnthropicException api(
            int status, String error_type, String message, String request_id, String body, boolean retryable) {
        String rendered = render_message(status, error_type, message);
        return new AnthropicException(Kind.API, rendered, status, error_type, request_id, body, retryable, null);
    }

    private static String render_message(int status, String error_type, String message) {
        StringBuilder out = new StringBuilder();
        out.append("Anthropic API error: status=").append(status);
        if (error_type != null) {
            out.append(" type=").append(error_type);
        }
        if (message != null && !message.isEmpty()) {
            out.append(" message=").append(message);
        }
        return out.toString();
    }
}
