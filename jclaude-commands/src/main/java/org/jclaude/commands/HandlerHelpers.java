package org.jclaude.commands;

import java.util.ArrayList;
import java.util.List;

/** Pure helper functions shared by parser, handlers, and renderers. */
final class HandlerHelpers {

    private HandlerHelpers() {}

    /**
     * Returns {@code args} trimmed if non-empty, otherwise {@code null}. Mirrors Rust
     * {@code normalize_optional_args}.
     */
    static String normalize_optional_args(String args) {
        if (args == null) {
            return null;
        }
        String trimmed = args.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Mirrors Rust {@code is_help_arg}. */
    static boolean is_help_arg(String arg) {
        return "help".equals(arg) || "-h".equals(arg) || "--help".equals(arg);
    }

    /**
     * Returns the leading tokens before a {@code help|-h|--help} flag in {@code args},
     * or {@code null} when none is present. Mirrors Rust {@code help_path_from_args}.
     */
    static List<String> help_path_from_args(String args) {
        if (args == null) {
            return null;
        }
        String[] parts = args.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (is_help_arg(parts[i])) {
                List<String> prefix = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    prefix.add(parts[j]);
                }
                return prefix;
            }
        }
        return null;
    }
}
