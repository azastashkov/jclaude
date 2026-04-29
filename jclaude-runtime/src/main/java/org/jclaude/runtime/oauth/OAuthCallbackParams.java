package org.jclaude.runtime.oauth;

import java.util.Optional;

/** Parsed query parameters returned to the local OAuth callback endpoint. */
public record OAuthCallbackParams(
        Optional<String> code, Optional<String> state, Optional<String> error, Optional<String> error_description) {

    public OAuthCallbackParams {
        code = code == null ? Optional.empty() : code;
        state = state == null ? Optional.empty() : state;
        error = error == null ? Optional.empty() : error;
        error_description = error_description == null ? Optional.empty() : error_description;
    }
}
