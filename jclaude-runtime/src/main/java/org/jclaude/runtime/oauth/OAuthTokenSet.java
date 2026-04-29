package org.jclaude.runtime.oauth;

import java.util.List;
import java.util.Optional;

/** Persisted OAuth token bundle. */
public record OAuthTokenSet(
        String access_token, Optional<String> refresh_token, Optional<Long> expires_at, List<String> scopes) {

    public OAuthTokenSet {
        scopes = List.copyOf(scopes);
        refresh_token = refresh_token == null ? Optional.empty() : refresh_token;
        expires_at = expires_at == null ? Optional.empty() : expires_at;
    }
}
