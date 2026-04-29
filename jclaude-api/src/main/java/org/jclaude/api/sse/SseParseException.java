package org.jclaude.api.sse;

/**
 * Thrown when an SSE frame's JSON payload cannot be deserialised into a
 * typed event. Carries provider/model context so callers can attribute the
 * malformed payload to its upstream source.
 */
public class SseParseException extends RuntimeException {

    private final String provider;
    private final String model;
    private final String payload;

    public SseParseException(String provider, String model, String payload, Throwable cause) {
        super(buildMessage(provider, model, payload), cause);
        this.provider = provider;
        this.model = model;
        this.payload = payload;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public String payload() {
        return payload;
    }

    private static String buildMessage(String provider, String model, String payload) {
        String snippet = payload == null ? "" : payload.length() > 256 ? payload.substring(0, 256) + "..." : payload;
        return "failed to deserialize SSE payload from provider=" + provider + " model=" + model + " body=" + snippet;
    }
}
