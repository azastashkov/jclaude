package org.jclaude.runtime.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Remote/upstream proxy helpers ported from {@code remote.rs}. */
public final class Remote {

    public static final String DEFAULT_REMOTE_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_SESSION_TOKEN_PATH = "/run/ccr/session_token";
    public static final String DEFAULT_SYSTEM_CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt";

    public static final List<String> UPSTREAM_PROXY_ENV_KEYS = List.of(
            "HTTPS_PROXY",
            "https_proxy",
            "NO_PROXY",
            "no_proxy",
            "SSL_CERT_FILE",
            "NODE_EXTRA_CA_CERTS",
            "REQUESTS_CA_BUNDLE",
            "CURL_CA_BUNDLE");

    public static final List<String> NO_PROXY_HOSTS = List.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "169.254.0.0/16",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "anthropic.com",
            ".anthropic.com",
            "*.anthropic.com",
            "github.com",
            "api.github.com",
            "*.github.com",
            "*.githubusercontent.com",
            "registry.npmjs.org",
            "index.crates.io");

    private Remote() {}

    public static Optional<String> read_token(Path path) throws IOException {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            String trimmed = contents.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(trimmed);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    public static String upstream_proxy_ws_url(String base_url) {
        String base = base_url;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String ws_base;
        if (base.startsWith("https://")) {
            ws_base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            ws_base = "ws://" + base.substring("http://".length());
        } else {
            ws_base = "wss://" + base;
        }
        return ws_base + "/v1/code/upstreamproxy/ws";
    }

    public static String no_proxy_list() {
        List<String> hosts = new ArrayList<>(NO_PROXY_HOSTS);
        hosts.add("pypi.org");
        hosts.add("files.pythonhosted.org");
        hosts.add("proxy.golang.org");
        return String.join(",", hosts);
    }

    public static Map<String, String> inherited_upstream_proxy_env(Map<String, String> env_map) {
        if (!env_map.containsKey("HTTPS_PROXY") || !env_map.containsKey("SSL_CERT_FILE")) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : UPSTREAM_PROXY_ENV_KEYS) {
            String v = env_map.get(key);
            if (v != null) {
                out.put(key, v);
            }
        }
        return out;
    }

    static Path default_ca_bundle_path() {
        String home = System.getenv("HOME");
        Path base = home == null ? Paths.get(".") : Paths.get(home);
        return base.resolve(".ccr").resolve("ca-bundle.crt");
    }

    static boolean env_truthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }
}
