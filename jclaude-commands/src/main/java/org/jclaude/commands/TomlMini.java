package org.jclaude.commands;

/**
 * Tiny line-based TOML/frontmatter helpers that mirror Rust {@code parse_toml_string}
 * and {@code parse_skill_frontmatter}. We intentionally do not pull in a TOML/YAML
 * library — only the same handful of fields the Rust port reads.
 */
final class TomlMini {

    private TomlMini() {}

    /**
     * Returns the quoted string value for {@code key = "value"}, or {@code null} if not found.
     * Mirrors Rust {@code parse_toml_string}.
     */
    static String parse_toml_string(String contents, String key) {
        String prefix = key + " =";
        for (String line : contents.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String value = trimmed.substring(prefix.length()).trim();
            if (!value.startsWith("\"") || !value.endsWith("\"") || value.length() < 2) {
                continue;
            }
            String inner = value.substring(1, value.length() - 1);
            if (!inner.isEmpty()) {
                return inner;
            }
        }
        return null;
    }

    /**
     * Parses YAML-ish frontmatter for {@code name} and {@code description}. Returns a 2-element
     * String array {@code [name, description]} (either may be {@code null}). Mirrors Rust
     * {@code parse_skill_frontmatter}.
     */
    static String[] parse_skill_frontmatter(String contents) {
        String[] lines = contents.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new String[] {null, null};
        }
        String name = null;
        String description = null;
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ("---".equals(trimmed)) {
                break;
            }
            if (trimmed.startsWith("name:")) {
                String value = unquote_frontmatter_value(
                        trimmed.substring("name:".length()).trim());
                if (!value.isEmpty()) {
                    name = value;
                }
                continue;
            }
            if (trimmed.startsWith("description:")) {
                String value = unquote_frontmatter_value(
                        trimmed.substring("description:".length()).trim());
                if (!value.isEmpty()) {
                    description = value;
                }
            }
        }
        return new String[] {name, description};
    }

    static String unquote_frontmatter_value(String value) {
        String unquoted;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            unquoted = value.substring(1, value.length() - 1);
        } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            unquoted = value.substring(1, value.length() - 1);
        } else {
            unquoted = value;
        }
        return unquoted.trim();
    }
}
