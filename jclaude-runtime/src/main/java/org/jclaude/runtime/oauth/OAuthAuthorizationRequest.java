package org.jclaude.runtime.oauth;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Request used to build an authorization URL. */
public final class OAuthAuthorizationRequest {

    private final String authorize_url;
    private final String client_id;
    private final String redirect_uri;
    private final List<String> scopes;
    private final String state;
    private final String code_challenge;
    private final PkceChallengeMethod code_challenge_method;
    private final TreeMap<String, String> extra_params;

    private OAuthAuthorizationRequest(
            String authorize_url,
            String client_id,
            String redirect_uri,
            List<String> scopes,
            String state,
            String code_challenge,
            PkceChallengeMethod code_challenge_method,
            TreeMap<String, String> extra_params) {
        this.authorize_url = authorize_url;
        this.client_id = client_id;
        this.redirect_uri = redirect_uri;
        this.scopes = List.copyOf(scopes);
        this.state = state;
        this.code_challenge = code_challenge;
        this.code_challenge_method = code_challenge_method;
        this.extra_params = new TreeMap<>(extra_params);
    }

    public static OAuthAuthorizationRequest from_config(
            OAuthConfig config, String redirect_uri, String state, PkceCodePair pkce) {
        return new OAuthAuthorizationRequest(
                config.authorize_url(),
                config.client_id(),
                redirect_uri,
                config.scopes(),
                state,
                pkce.challenge(),
                pkce.challenge_method(),
                new TreeMap<>());
    }

    public OAuthAuthorizationRequest with_extra_param(String key, String value) {
        TreeMap<String, String> next = new TreeMap<>(this.extra_params);
        next.put(key, value);
        return new OAuthAuthorizationRequest(
                authorize_url, client_id, redirect_uri, scopes, state, code_challenge, code_challenge_method, next);
    }

    public String build_url() {
        List<String[]> params = new ArrayList<>();
        params.add(new String[] {"response_type", "code"});
        params.add(new String[] {"client_id", client_id});
        params.add(new String[] {"redirect_uri", redirect_uri});
        params.add(new String[] {"scope", String.join(" ", scopes)});
        params.add(new String[] {"state", state});
        params.add(new String[] {"code_challenge", code_challenge});
        params.add(new String[] {"code_challenge_method", code_challenge_method.as_str()});
        for (var e : extra_params.entrySet()) {
            params.add(new String[] {e.getKey(), e.getValue()});
        }
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                query.append('&');
            }
            query.append(Oauth.percent_encode(params.get(i)[0]))
                    .append('=')
                    .append(Oauth.percent_encode(params.get(i)[1]));
        }
        char sep = authorize_url.contains("?") ? '&' : '?';
        return authorize_url + sep + query;
    }

    public String state() {
        return state;
    }

    public String redirect_uri() {
        return redirect_uri;
    }
}
