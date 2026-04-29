package org.jclaude.cli;

import java.util.Locale;

/** Supported `--output-format` values. */
public enum OutputFormat {
    TEXT,
    JSON;

    public static OutputFormat parse(String raw) {
        if (raw == null) {
            return TEXT;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "json" -> JSON;
            default -> throw new IllegalArgumentException("unknown output format: " + raw);
        };
    }
}
