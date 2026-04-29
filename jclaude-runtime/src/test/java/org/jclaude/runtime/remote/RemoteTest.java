package org.jclaude.runtime.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteTest {

    @Test
    void remote_context_reads_env_state() {
        Map<String, String> env = Map.of(
                "CLAUDE_CODE_REMOTE", "true",
                "CLAUDE_CODE_REMOTE_SESSION_ID", "session-123",
                "ANTHROPIC_BASE_URL", "https://remote.test");

        RemoteSessionContext context = RemoteSessionContext.from_env_map(env);

        assertThat(context.enabled()).isTrue();
        assertThat(context.session_id()).contains("session-123");
        assertThat(context.base_url()).isEqualTo("https://remote.test");
    }

    @Test
    void bootstrap_fails_open_when_token_or_session_is_missing() {
        Map<String, String> env = Map.of(
                "CLAUDE_CODE_REMOTE", "1",
                "CCR_UPSTREAM_PROXY_ENABLED", "true");

        UpstreamProxyBootstrap bootstrap = UpstreamProxyBootstrap.from_env_map(env);

        assertThat(bootstrap.should_enable()).isFalse();
        assertThat(bootstrap.state_for_port(8080).enabled()).isFalse();
    }

    @Test
    void bootstrap_derives_proxy_state_and_env(@TempDir Path root) throws Exception {
        Path token_path = root.resolve("session_token");
        Files.writeString(token_path, "secret-token\n");

        Map<String, String> env = new HashMap<>();
        env.put("CLAUDE_CODE_REMOTE", "1");
        env.put("CCR_UPSTREAM_PROXY_ENABLED", "true");
        env.put("CLAUDE_CODE_REMOTE_SESSION_ID", "session-123");
        env.put("ANTHROPIC_BASE_URL", "https://remote.test");
        env.put("CCR_SESSION_TOKEN_PATH", token_path.toString());
        env.put("CCR_CA_BUNDLE_PATH", root.resolve("ca-bundle.crt").toString());

        UpstreamProxyBootstrap bootstrap = UpstreamProxyBootstrap.from_env_map(env);

        assertThat(bootstrap.should_enable()).isTrue();
        assertThat(bootstrap.token()).contains("secret-token");
        assertThat(bootstrap.ws_url()).isEqualTo("wss://remote.test/v1/code/upstreamproxy/ws");

        UpstreamProxyState state = bootstrap.state_for_port(9443);
        assertThat(state.enabled()).isTrue();
        Map<String, String> proxy_env = state.subprocess_env();
        assertThat(proxy_env).containsEntry("HTTPS_PROXY", "http://127.0.0.1:9443");
        assertThat(proxy_env)
                .containsEntry("SSL_CERT_FILE", root.resolve("ca-bundle.crt").toString());
    }

    @Test
    void token_reader_trims_and_handles_missing_files(@TempDir Path root) throws Exception {
        Path token_path = root.resolve("session_token");
        Files.writeString(token_path, " abc123 \n");

        assertThat(Remote.read_token(token_path)).contains("abc123");
        assertThat(Remote.read_token(root.resolve("missing"))).isEmpty();
    }

    @Test
    void inherited_proxy_env_requires_proxy_and_ca() {
        Map<String, String> env = Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:8888",
                "SSL_CERT_FILE", "/tmp/ca-bundle.crt",
                "NO_PROXY", "localhost");

        Map<String, String> inherited = Remote.inherited_upstream_proxy_env(env);

        assertThat(inherited).hasSize(3);
        assertThat(inherited).containsEntry("NO_PROXY", "localhost");
        assertThat(Remote.inherited_upstream_proxy_env(Map.of())).isEmpty();
    }

    @Test
    void helper_outputs_match_expected_shapes() {
        assertThat(Remote.upstream_proxy_ws_url("http://localhost:3000/"))
                .isEqualTo("ws://localhost:3000/v1/code/upstreamproxy/ws");
        assertThat(Remote.no_proxy_list()).contains("anthropic.com");
        assertThat(Remote.no_proxy_list()).contains("github.com");
    }
}
