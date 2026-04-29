package org.jclaude.runtime.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** OAuth helpers ported from {@code oauth.rs}. */
public final class Oauth {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Oauth() {}

    public static PkceCodePair generate_pkce_pair() {
        String verifier = generate_random_token(32);
        return new PkceCodePair(verifier, code_challenge_s256(verifier), PkceChallengeMethod.S256);
    }

    public static String generate_state() {
        return generate_random_token(32);
    }

    public static String code_challenge_s256(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return base64url_encode(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String loopback_redirect_uri(int port) {
        return "http://localhost:" + port + "/callback";
    }

    public static Path credentials_path() {
        return credentials_home_dir().resolve("credentials.json");
    }

    public static Optional<OAuthTokenSet> load_oauth_credentials() throws IOException {
        Path path = credentials_path();
        ObjectNode root = read_credentials_root(path);
        if (!root.has("oauth") || root.get("oauth").isNull()) {
            return Optional.empty();
        }
        JsonNode node = root.get("oauth");
        String access_token = node.path("accessToken").asText("");
        String refresh = node.has("refreshToken") && !node.get("refreshToken").isNull()
                ? node.get("refreshToken").asText()
                : null;
        Long expires = node.has("expiresAt") && !node.get("expiresAt").isNull()
                ? node.get("expiresAt").asLong()
                : null;
        List<String> scopes = new ArrayList<>();
        if (node.has("scopes") && node.get("scopes").isArray()) {
            node.get("scopes").forEach(n -> scopes.add(n.asText()));
        }
        return Optional.of(
                new OAuthTokenSet(access_token, Optional.ofNullable(refresh), Optional.ofNullable(expires), scopes));
    }

    public static void save_oauth_credentials(OAuthTokenSet token_set) throws IOException {
        Path path = credentials_path();
        ObjectNode root = read_credentials_root(path);
        ObjectNode oauth = JSON.createObjectNode();
        oauth.put("accessToken", token_set.access_token());
        token_set.refresh_token().ifPresent(v -> oauth.put("refreshToken", v));
        token_set.expires_at().ifPresent(v -> oauth.put("expiresAt", v));
        var scopes_node = oauth.putArray("scopes");
        for (String s : token_set.scopes()) {
            scopes_node.add(s);
        }
        root.set("oauth", oauth);
        write_credentials_root(path, root);
    }

    public static void clear_oauth_credentials() throws IOException {
        Path path = credentials_path();
        ObjectNode root = read_credentials_root(path);
        root.remove("oauth");
        write_credentials_root(path, root);
    }

    public static OAuthCallbackParams parse_oauth_callback_request_target(String target) {
        int q_idx = target.indexOf('?');
        String path = q_idx < 0 ? target : target.substring(0, q_idx);
        String query = q_idx < 0 ? "" : target.substring(q_idx + 1);
        if (!path.equals("/callback")) {
            throw new IllegalArgumentException("unexpected callback path: " + path);
        }
        return parse_oauth_callback_query(query);
    }

    public static OAuthCallbackParams parse_oauth_callback_query(String query) {
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = pair;
                value = "";
            } else {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }
            params.put(percent_decode(key), percent_decode(value));
        }
        return new OAuthCallbackParams(
                Optional.ofNullable(params.get("code")),
                Optional.ofNullable(params.get("state")),
                Optional.ofNullable(params.get("error")),
                Optional.ofNullable(params.get("error_description")));
    }

    /** Start a 127.0.0.1:0 callback server, return resolved port and a future-like callback. */
    public static CallbackServerHandle start_callback_server(java.util.function.Consumer<OAuthCallbackParams> sink)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", exchange -> {
            String target = exchange.getRequestURI().toString();
            try {
                OAuthCallbackParams params = parse_oauth_callback_request_target(target);
                sink.accept(params);
                byte[] body = "OAuth callback received. You may close this tab.".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            } catch (RuntimeException e) {
                byte[] body = ("invalid callback: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            }
        });
        server.start();
        return new CallbackServerHandle(server, server.getAddress().getPort());
    }

    public record CallbackServerHandle(HttpServer server, int port) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String generate_random_token(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return base64url_encode(buf);
    }

    private static Path credentials_home_dir() {
        String override = System.getenv("CLAW_CONFIG_HOME");
        if (override != null) {
            return Paths.get(override);
        }
        String home = System.getenv("HOME");
        if (home == null) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null) {
            throw new IllegalStateException(
                    "HOME is not set (on Windows, set USERPROFILE or HOME, or use CLAW_CONFIG_HOME to point directly at the config directory)");
        }
        return Paths.get(home).resolve(".claw");
    }

    private static ObjectNode read_credentials_root(Path path) throws IOException {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            if (contents.trim().isEmpty()) {
                return JSON.createObjectNode();
            }
            JsonNode parsed = JSON.readTree(contents);
            if (!parsed.isObject()) {
                throw new IOException("credentials file must contain a JSON object");
            }
            return (ObjectNode) parsed;
        } catch (NoSuchFileException e) {
            return JSON.createObjectNode();
        }
    }

    private static void write_credentials_root(Path path, ObjectNode root) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String rendered = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.writeString(temp, rendered, StandardCharsets.UTF_8);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String base64url_encode(byte[] bytes) {
        char[] table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i + 3 <= bytes.length) {
            int block = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8) | (bytes[i + 2] & 0xFF);
            out.append(table[(block >> 18) & 0x3F]);
            out.append(table[(block >> 12) & 0x3F]);
            out.append(table[(block >> 6) & 0x3F]);
            out.append(table[block & 0x3F]);
            i += 3;
        }
        int rem = bytes.length - i;
        if (rem == 1) {
            int block = (bytes[i] & 0xFF) << 16;
            out.append(table[(block >> 18) & 0x3F]);
            out.append(table[(block >> 12) & 0x3F]);
        } else if (rem == 2) {
            int block = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8);
            out.append(table[(block >> 18) & 0x3F]);
            out.append(table[(block >> 12) & 0x3F]);
            out.append(table[(block >> 6) & 0x3F]);
        }
        return out.toString();
    }

    public static String percent_encode(String value) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int u = b & 0xFF;
            boolean unreserved = (u >= 'A' && u <= 'Z')
                    || (u >= 'a' && u <= 'z')
                    || (u >= '0' && u <= '9')
                    || u == '-'
                    || u == '_'
                    || u == '.'
                    || u == '~';
            if (unreserved) {
                out.append((char) u);
            } else {
                out.append('%').append(String.format("%02X", u));
            }
        }
        return out.toString();
    }

    public static String percent_decode(String value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b == '%' && i + 2 < bytes.length) {
                int hi = decode_hex(bytes[i + 1] & 0xFF);
                int lo = decode_hex(bytes[i + 2] & 0xFF);
                out.write((hi << 4) | lo);
                i += 3;
            } else if (b == '+') {
                out.write(' ');
                i++;
            } else {
                out.write(b);
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int decode_hex(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        throw new IllegalArgumentException("invalid percent byte: " + b);
    }

    /** Avoids unused-iterator warnings. */
    static <T> Iterator<T> iter(List<T> list) {
        return list.iterator();
    }
}
