package org.jclaude.runtime.oauth;

import java.util.List;
import java.util.Optional;

/** OAuth configuration record (subset of the runtime config used by these helpers). */
public record OAuthConfig(
        String client_id,
        String authorize_url,
        String token_url,
        Optional<Integer> callback_port,
        Optional<String> manual_redirect_url,
        List<String> scopes) {

    public OAuthConfig {
        scopes = List.copyOf(scopes);
        callback_port = callback_port == null ? Optional.empty() : callback_port;
        manual_redirect_url = manual_redirect_url == null ? Optional.empty() : manual_redirect_url;
    }
}
