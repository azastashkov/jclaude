package org.jclaude.api.providers;

import java.util.Optional;

/**
 * Exception thrown by {@link OpenAiCompatClient} when the upstream provider
 * returns a non-success HTTP status, when the body is malformed JSON, or when
 * a stream frame carries an embedded error envelope. Models the Rust
 * {@code ApiError} variants relevant to the OpenAI-compat client without
 * pulling in the full Rust enum hierarchy.
 */
public final class OpenAiCompatException extends RuntimeException {

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

    private final Kind kind;
    private final Integer status;
    private final String error_type;
    private final String request_id;
    private final String body;
    private final boolean retryable;
    private final String suggested_action;

    private OpenAiCompatException(
            Kind kind,
            String message,
            Integer status,
            String error_type,
            String request_id,
            String body,
            boolean retryable,
            String suggested_action,
            Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.error_type = error_type;
        this.request_id = request_id;
        this.body = body;
        this.retryable = retryable;
        this.suggested_action = suggested_action;
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

    public Optional<String> request_id() {
        return Optional.ofNullable(request_id);
    }

    public Optional<String> body() {
        return Optional.ofNullable(body);
    }

    public boolean is_retryable() {
        return retryable;
    }

    public Optional<String> suggested_action() {
        return Optional.ofNullable(suggested_action);
    }

    public static OpenAiCompatException missing_credentials(String provider, java.util.List<String> envVars) {
        return new OpenAiCompatException(
                Kind.MISSING_CREDENTIALS,
                "missing " + provider + " credentials; set one of " + envVars,
                null,
                null,
                null,
                null,
                false,
                null,
                null);
    }

    public static OpenAiCompatException request_body_size_exceeded(
            int estimated_bytes, int max_bytes, String provider) {
        return new OpenAiCompatException(
                Kind.REQUEST_BODY_SIZE_EXCEEDED,
                "request body size " + estimated_bytes + " exceeds " + provider + " limit of " + max_bytes
                        + " bytes; reduce prompt size or context window before retrying",
                null,
                null,
                null,
                null,
                false,
                null,
                null);
    }

    public static OpenAiCompatException context_window_exceeded(Providers.ContextWindowExceededError error) {
        OpenAiCompatException ex = new OpenAiCompatException(
                Kind.CONTEXT_WINDOW_EXCEEDED, error.to_message(), null, null, null, null, false, null, null);
        return ex;
    }

    public static OpenAiCompatException io(String message, Throwable cause) {
        return new OpenAiCompatException(Kind.IO, message, null, null, null, null, true, null, cause);
    }

    public static OpenAiCompatException invalid_sse_frame(String message) {
        return new OpenAiCompatException(Kind.INVALID_SSE_FRAME, message, null, null, null, null, false, null, null);
    }

    public static OpenAiCompatException json_deserialize(String provider, String model, String body, Throwable cause) {
        return new OpenAiCompatException(
                Kind.JSON_DESERIALIZE,
                "failed to deserialize " + provider + " response for model " + model + ": " + cause.getMessage(),
                null,
                null,
                null,
                body,
                false,
                null,
                cause);
    }

    public static OpenAiCompatException retries_exhausted(int attempts, OpenAiCompatException last) {
        OpenAiCompatException ex = new OpenAiCompatException(
                Kind.RETRIES_EXHAUSTED,
                "retries exhausted after " + attempts + " attempts: " + last.getMessage(),
                last.status,
                last.error_type,
                last.request_id,
                last.body,
                false,
                last.suggested_action,
                last);
        return ex;
    }

    public static OpenAiCompatException api(
            int status,
            String error_type,
            String message,
            String request_id,
            String body,
            boolean retryable,
            String suggested_action) {
        return new OpenAiCompatException(
                Kind.API, message, status, error_type, request_id, body, retryable, suggested_action, null);
    }
}
