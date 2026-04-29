package org.jclaude.runtime.mcp;

import java.util.Objects;

/**
 * Helper utilities mirroring the Rust top-level {@code mcp.rs}: name normalisation for tool
 * prefixes, qualified-name composition, and CCR proxy URL unwrapping.
 */
public final class Mcp {

    private static final String CLAUDEAI_SERVER_PREFIX = "claude.ai ";
    private static final String[] CCR_PROXY_PATH_MARKERS = {"/v2/session_ingress/shttp/mcp/", "/v2/ccr-sessions/"};

    private Mcp() {}

    public static String normalize_name_for_mcp(String name) {
        Objects.requireNonNull(name, "name");
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean keep = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '-';
            sb.append(keep ? ch : '_');
        }
        String normalized = sb.toString();
        if (name.startsWith(CLAUDEAI_SERVER_PREFIX)) {
            normalized = trimUnderscores(collapseUnderscores(normalized));
        }
        return normalized;
    }

    public static String mcp_tool_prefix(String server_name) {
        return "mcp__" + normalize_name_for_mcp(server_name) + "__";
    }

    public static String mcp_tool_name(String server_name, String tool_name) {
        return mcp_tool_prefix(server_name) + normalize_name_for_mcp(tool_name);
    }

    public static String unwrap_ccr_proxy_url(String url) {
        Objects.requireNonNull(url, "url");
        boolean matches = false;
        for (String marker : CCR_PROXY_PATH_MARKERS) {
            if (url.contains(marker)) {
                matches = true;
                break;
            }
        }
        if (!matches) {
            return url;
        }
        int qmark = url.indexOf('?');
        if (qmark < 0) {
            return url;
        }
        String query = url.substring(qmark + 1);
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals("mcp_url")) {
                return percentDecode(parts[1]);
            }
        }
        return url;
    }

    private static String collapseUnderscores(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        boolean last_was_underscore = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '_') {
                if (!last_was_underscore) {
                    sb.append(ch);
                }
                last_was_underscore = true;
            } else {
                sb.append(ch);
                last_was_underscore = false;
            }
        }
        return sb.toString();
    }

    private static String trimUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String percentDecode(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var out = new java.io.ByteArrayOutputStream(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            byte b = bytes[i];
            if (b == '%' && i + 2 < bytes.length) {
                String hex = value.substring(i + 1, i + 3);
                try {
                    int decoded = Integer.parseInt(hex, 16);
                    out.write(decoded);
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                    out.write(b);
                    i++;
                    continue;
                }
            }
            if (b == '+') {
                out.write(' ');
                i++;
                continue;
            }
            out.write(b);
            i++;
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
