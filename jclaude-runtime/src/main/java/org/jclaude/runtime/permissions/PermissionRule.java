package org.jclaude.runtime.permissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class PermissionRule {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> SUBJECT_KEYS = List.of(
            "command",
            "path",
            "file_path",
            "filePath",
            "notebook_path",
            "notebookPath",
            "url",
            "pattern",
            "code",
            "message");

    final String raw;
    final String tool_name;
    final PermissionRuleMatcher matcher;

    PermissionRule(String raw, String tool_name, PermissionRuleMatcher matcher) {
        this.raw = raw;
        this.tool_name = tool_name;
        this.matcher = matcher;
    }

    static PermissionRule parse(String raw) {
        String trimmed = raw.trim();
        Optional<Integer> open = find_first_unescaped(trimmed, '(');
        Optional<Integer> close = find_last_unescaped(trimmed, ')');

        if (open.isPresent() && close.isPresent()) {
            int o = open.get();
            int c = close.get();
            if (c == trimmed.length() - 1 && o < c) {
                String toolName = trimmed.substring(0, o).trim();
                String content = trimmed.substring(o + 1, c);
                if (!toolName.isEmpty()) {
                    PermissionRuleMatcher matcher = parse_rule_matcher(content);
                    return new PermissionRule(trimmed, toolName, matcher);
                }
            }
        }

        return new PermissionRule(trimmed, trimmed, new PermissionRuleMatcher.Any());
    }

    boolean matches(String toolName, String input) {
        if (!this.tool_name.equals(toolName)) {
            return false;
        }
        if (matcher instanceof PermissionRuleMatcher.Any) {
            return true;
        }
        if (matcher instanceof PermissionRuleMatcher.Exact exact) {
            Optional<String> candidate = extract_permission_subject(input);
            return candidate.isPresent() && candidate.get().equals(exact.expected());
        }
        if (matcher instanceof PermissionRuleMatcher.Prefix prefix) {
            Optional<String> candidate = extract_permission_subject(input);
            return candidate.isPresent() && candidate.get().startsWith(prefix.prefix());
        }
        return false;
    }

    static PermissionRuleMatcher parse_rule_matcher(String content) {
        String unescaped = unescape_rule_content(content.trim());
        if (unescaped.isEmpty() || unescaped.equals("*")) {
            return new PermissionRuleMatcher.Any();
        }
        if (unescaped.endsWith(":*")) {
            String prefix = unescaped.substring(0, unescaped.length() - 2);
            return new PermissionRuleMatcher.Prefix(prefix);
        }
        return new PermissionRuleMatcher.Exact(unescaped);
    }

    static String unescape_rule_content(String content) {
        return content.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
    }

    static Optional<Integer> find_first_unescaped(String value, char needle) {
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\') {
                escaped = !escaped;
                continue;
            }
            if (ch == needle && !escaped) {
                return Optional.of(i);
            }
            escaped = false;
        }
        return Optional.empty();
    }

    static Optional<Integer> find_last_unescaped(String value, char needle) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (value.charAt(i) != needle) {
                continue;
            }
            int backslashes = 0;
            for (int j = i - 1; j >= 0; j--) {
                if (value.charAt(j) == '\\') {
                    backslashes++;
                } else {
                    break;
                }
            }
            if (backslashes % 2 == 0) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    static Optional<String> extract_permission_subject(String input) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(input);
            if (parsed != null && parsed.isObject()) {
                for (String key : SUBJECT_KEYS) {
                    JsonNode value = parsed.get(key);
                    if (value != null && value.isTextual()) {
                        return Optional.of(value.asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to plain-text handling
        }

        if (input.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(input);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionRule other)) {
            return false;
        }
        return raw.equals(other.raw) && tool_name.equals(other.tool_name) && Objects.equals(matcher, other.matcher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, tool_name, matcher);
    }
}
