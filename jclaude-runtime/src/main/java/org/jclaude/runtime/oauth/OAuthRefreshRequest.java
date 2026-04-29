package org.jclaude.runtime.oauth;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Refresh-token request. */
public record OAuthRefreshRequest(String grant_type, String refresh_token, String client_id, List<String> scopes) {

    public OAuthRefreshRequest {
        scopes = List.copyOf(scopes);
    }

    public static OAuthRefreshRequest from_config(
            OAuthConfig config, String refresh_token, Optional<List<String>> scopes) {
        return new OAuthRefreshRequest(
                "refresh_token", refresh_token, config.client_id(), scopes.orElse(config.scopes()));
    }

    public TreeMap<String, String> form_params() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("grant_type", grant_type);
        p.put("refresh_token", refresh_token);
        p.put("client_id", client_id);
        p.put("scope", String.join(" ", scopes));
        return p;
    }
}
