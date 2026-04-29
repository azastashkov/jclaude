package org.jclaude.runtime.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OauthTest {

    private static OAuthConfig sample_config() {
        return new OAuthConfig(
                "runtime-client",
                "https://console.test/oauth/authorize",
                "https://console.test/oauth/token",
                Optional.of(4545),
                Optional.of("https://console.test/oauth/callback"),
                List.of("org:read", "user:write"));
    }

    @Test
    void s256_challenge_matches_expected_vector() {
        assertThat(Oauth.code_challenge_s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void generates_pkce_pair_and_state() {
        PkceCodePair pair = Oauth.generate_pkce_pair();
        String state = Oauth.generate_state();
        assertThat(pair.verifier()).isNotEmpty();
        assertThat(pair.challenge()).isNotEmpty();
        assertThat(state).isNotEmpty();
    }

    @Test
    void builds_authorize_url_and_form_requests() {
        OAuthConfig config = sample_config();
        PkceCodePair pair = Oauth.generate_pkce_pair();
        String url = OAuthAuthorizationRequest.from_config(config, Oauth.loopback_redirect_uri(4545), "state-123", pair)
                .with_extra_param("login_hint", "user@example.com")
                .build_url();
        assertThat(url).startsWith("https://console.test/oauth/authorize?");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=runtime-client");
        assertThat(url).contains("scope=org%3Aread%20user%3Awrite");
        assertThat(url).contains("login_hint=user%40example.com");

        OAuthTokenExchangeRequest exchange = OAuthTokenExchangeRequest.from_config(
                config, "auth-code", "state-123", pair.verifier(), Oauth.loopback_redirect_uri(4545));
        assertThat(exchange.form_params().get("grant_type")).isEqualTo("authorization_code");

        OAuthRefreshRequest refresh = OAuthRefreshRequest.from_config(config, "refresh-token", Optional.empty());
        assertThat(refresh.form_params().get("scope")).isEqualTo("org:read user:write");
    }

    @Test
    void parses_callback_query_and_target() {
        OAuthCallbackParams params =
                Oauth.parse_oauth_callback_query("code=abc123&state=state-1&error_description=needs%20login");
        assertThat(params.code()).contains("abc123");
        assertThat(params.state()).contains("state-1");
        assertThat(params.error_description()).contains("needs login");

        OAuthCallbackParams target_params = Oauth.parse_oauth_callback_request_target("/callback?code=abc&state=xyz");
        assertThat(target_params.code()).contains("abc");
        assertThat(target_params.state()).contains("xyz");
        assertThatThrownBy(() -> Oauth.parse_oauth_callback_request_target("/wrong?code=abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
