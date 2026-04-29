package org.jclaude.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Header + body shaping for Anthropic API calls. Mirrors Rust's AnthropicRequestProfile: emits
 * {@code anthropic-version} / {@code user-agent} / {@code anthropic-beta} header pairs and merges
 * extra body fields and a {@code betas} array into outgoing JSON.
 */
public final class AnthropicRequestProfile {

    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    public static final String DEFAULT_AGENTIC_BETA = "claude-code-20250219";
    public static final String DEFAULT_PROMPT_CACHING_SCOPE_BETA = "prompt-caching-scope-2026-01-05";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String anthropic_version;
    private final ClientIdentity client_identity;
    private final List<String> betas;
    private final Map<String, JsonNode> extra_body;

    private AnthropicRequestProfile(
            String anthropic_version,
            ClientIdentity client_identity,
            List<String> betas,
            Map<String, JsonNode> extra_body) {
        this.anthropic_version = anthropic_version;
        this.client_identity = client_identity;
        this.betas = List.copyOf(betas);
        this.extra_body = new LinkedHashMap<>(extra_body);
    }

    public static AnthropicRequestProfile of(ClientIdentity identity) {
        List<String> betas = new ArrayList<>();
        betas.add(DEFAULT_AGENTIC_BETA);
        betas.add(DEFAULT_PROMPT_CACHING_SCOPE_BETA);
        return new AnthropicRequestProfile(DEFAULT_ANTHROPIC_VERSION, identity, betas, new LinkedHashMap<>());
    }

    public static AnthropicRequestProfile defaults() {
        return of(ClientIdentity.defaults());
    }

    public String anthropic_version() {
        return anthropic_version;
    }

    public ClientIdentity client_identity() {
        return client_identity;
    }

    public List<String> betas() {
        return betas;
    }

    public Map<String, JsonNode> extra_body() {
        return new LinkedHashMap<>(extra_body);
    }

    public AnthropicRequestProfile with_beta(String beta) {
        if (betas.contains(beta)) {
            return this;
        }
        List<String> next = new ArrayList<>(betas);
        next.add(beta);
        return new AnthropicRequestProfile(anthropic_version, client_identity, next, extra_body);
    }

    public AnthropicRequestProfile with_extra_body(String key, JsonNode value) {
        Map<String, JsonNode> next = new LinkedHashMap<>(extra_body);
        next.put(key, value);
        return new AnthropicRequestProfile(anthropic_version, client_identity, betas, next);
    }

    public List<Map.Entry<String, String>> header_pairs() {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry("anthropic-version", anthropic_version));
        headers.add(Map.entry("user-agent", client_identity.user_agent()));
        if (!betas.isEmpty()) {
            headers.add(Map.entry("anthropic-beta", String.join(",", betas)));
        }
        return List.copyOf(headers);
    }

    /** Renders a JSON request body merged with extra_body and the {@code betas} field. */
    public ObjectNode render_json_body(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("request body must serialize to a JSON object");
        }
        ObjectNode body = ((ObjectNode) request).deepCopy();
        for (Map.Entry<String, JsonNode> entry : extra_body.entrySet()) {
            body.set(entry.getKey(), entry.getValue());
        }
        if (!betas.isEmpty()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (String beta : betas) {
                array.add(beta);
            }
            body.set("betas", array);
        }
        return body;
    }
}
