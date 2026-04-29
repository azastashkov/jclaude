package org.jclaude.runtime.oauth;

import java.util.TreeMap;

/** Token exchange request. */
public record OAuthTokenExchangeRequest(
        String grant_type, String code, String redirect_uri, String client_id, String code_verifier, String state) {

    public static OAuthTokenExchangeRequest from_config(
            OAuthConfig config, String code, String state, String verifier, String redirect_uri) {
        return new OAuthTokenExchangeRequest(
                "authorization_code", code, redirect_uri, config.client_id(), verifier, state);
    }

    public TreeMap<String, String> form_params() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("grant_type", grant_type);
        p.put("code", code);
        p.put("redirect_uri", redirect_uri);
        p.put("client_id", client_id);
        p.put("code_verifier", code_verifier);
        p.put("state", state);
        return p;
    }
}
