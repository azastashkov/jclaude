package org.jclaude.runtime.remote;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolved upstream proxy state for the current session. */
public record UpstreamProxyState(
        boolean enabled, Optional<String> proxy_url, Optional<Path> ca_bundle_path, String no_proxy) {

    public UpstreamProxyState {
        proxy_url = proxy_url == null ? Optional.empty() : proxy_url;
        ca_bundle_path = ca_bundle_path == null ? Optional.empty() : ca_bundle_path;
    }

    public static UpstreamProxyState disabled() {
        return new UpstreamProxyState(false, Optional.empty(), Optional.empty(), Remote.no_proxy_list());
    }

    public Map<String, String> subprocess_env() {
        if (!enabled || proxy_url.isEmpty() || ca_bundle_path.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        String proxy = proxy_url.get();
        String ca = ca_bundle_path.get().toString();
        out.put("HTTPS_PROXY", proxy);
        out.put("https_proxy", proxy);
        out.put("NO_PROXY", no_proxy);
        out.put("no_proxy", no_proxy);
        out.put("SSL_CERT_FILE", ca);
        out.put("NODE_EXTRA_CA_CERTS", ca);
        out.put("REQUESTS_CA_BUNDLE", ca);
        out.put("CURL_CA_BUNDLE", ca);
        return out;
    }
}
