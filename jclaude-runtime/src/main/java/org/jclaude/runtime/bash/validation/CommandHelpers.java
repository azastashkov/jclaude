package org.jclaude.runtime.bash.validation;

/** Shared parsing helpers for bash command validation. */
final class CommandHelpers {
    private CommandHelpers() {}

    /** Extract the first bare command from a pipeline/chain, stripping env vars and sudo. */
    static String extract_first_command(String command) {
        String trimmed = command.trim();
        String remaining = trimmed;
        while (true) {
            String next = remaining.stripLeading();
            int eqPos = next.indexOf('=');
            if (eqPos > 0) {
                String beforeEq = next.substring(0, eqPos);
                if (!beforeEq.isEmpty() && all_alnum_or_underscore(beforeEq)) {
                    String afterEq = next.substring(eqPos + 1);
                    Integer space = find_end_of_value(afterEq);
                    if (space != null) {
                        remaining = afterEq.substring(space);
                        continue;
                    }
                    return "";
                }
            }
            break;
        }

        String[] parts = remaining.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "";
        }
        return parts[0];
    }

    /** Extract the command following "sudo" (skip sudo flags). */
    static String extract_sudo_inner(String command) {
        String[] parts = command.trim().split("\\s+");
        int sudoIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("sudo".equals(parts[i])) {
                sudoIdx = i;
                break;
            }
        }
        if (sudoIdx < 0) {
            return "";
        }
        for (int i = sudoIdx + 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.startsWith("-")) {
                int offset = command.indexOf(part);
                if (offset < 0) {
                    offset = 0;
                }
                return command.substring(offset);
            }
        }
        return "";
    }

    /** Find the end of a value in `KEY=value rest` (handles basic quoting). */
    static Integer find_end_of_value(String s) {
        s = s.stripLeading();
        if (s.isEmpty()) {
            return null;
        }
        char first = s.charAt(0);
        if (first == '"' || first == '\'') {
            char quote = first;
            int i = 1;
            while (i < s.length()) {
                if (s.charAt(i) == quote && (i == 0 || s.charAt(i - 1) != '\\')) {
                    i++;
                    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    return i < s.length() ? i : null;
                }
                i++;
            }
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return null;
    }

    private static boolean all_alnum_or_underscore(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
