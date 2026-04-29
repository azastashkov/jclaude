package org.jclaude.runtime.remote;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/** Resolved upstream proxy bootstrap. */
public record UpstreamProxyBootstrap(
        RemoteSessionContext remote,
        boolean upstream_proxy_enabled,
        Path token_path,
        Path ca_bundle_path,
        Path system_ca_path,
        Optional<String> token) {

    public UpstreamProxyBootstrap {
        token = token == null ? Optional.empty() : token;
    }

    public static UpstreamProxyBootstrap from_env() {
        return from_env_map(System.getenv());
    }

    public static UpstreamProxyBootstrap from_env_map(Map<String, String> env_map) {
        RemoteSessionContext remote = RemoteSessionContext.from_env_map(env_map);
        Path token_path = path_from_env(env_map.get("CCR_SESSION_TOKEN_PATH"))
                .orElse(Paths.get(Remote.DEFAULT_SESSION_TOKEN_PATH));
        Path system_ca_path =
                path_from_env(env_map.get("CCR_SYSTEM_CA_BUNDLE")).orElse(Paths.get(Remote.DEFAULT_SYSTEM_CA_BUNDLE));
        Path ca_bundle_path =
                path_from_env(env_map.get("CCR_CA_BUNDLE_PATH")).orElseGet(Remote::default_ca_bundle_path);
        Optional<String> token;
        try {
            token = Remote.read_token(token_path);
        } catch (Exception e) {
            token = Optional.empty();
        }
        return new UpstreamProxyBootstrap(
                remote,
                Remote.env_truthy(env_map.get("CCR_UPSTREAM_PROXY_ENABLED")),
                token_path,
                ca_bundle_path,
                system_ca_path,
                token);
    }

    private static Optional<Path> path_from_env(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Paths.get(value));
    }

    public boolean should_enable() {
        return remote.enabled() && upstream_proxy_enabled && remote.session_id().isPresent() && token.isPresent();
    }

    public String ws_url() {
        return Remote.upstream_proxy_ws_url(remote.base_url());
    }

    public UpstreamProxyState state_for_port(int port) {
        if (!should_enable()) {
            return UpstreamProxyState.disabled();
        }
        return new UpstreamProxyState(
                true, Optional.of("http://127.0.0.1:" + port), Optional.of(ca_bundle_path), Remote.no_proxy_list());
    }
}
