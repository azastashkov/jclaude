package org.jclaude.cli;

import java.util.Locale;

/**
 * Supported {@code --style} values. Controls the in-REPL look-and-feel of the streaming TUI.
 *
 * <ul>
 *   <li>{@link #JCLAUDE} — the default. Tool results render inside rounded {@code ╭─ name ─╮}
 *       boxes, ordered as {@code [tool boxes] → [turn footer] → [model prose]}.
 *   <li>{@link #CLAUDE_CODE} — Claude Code CLI feel. Tool calls show on a single line prefixed
 *       with {@code ●} and a one-line input summary, the result body is indented under
 *       {@code ⎿  …}, and tool blocks + model prose interleave chronologically.
 * </ul>
 */
public enum OutputStyle {
    JCLAUDE,
    CLAUDE_CODE;

    public static OutputStyle parse(String raw) {
        if (raw == null) {
            return JCLAUDE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "jclaude", "default", "boxed" -> JCLAUDE;
            case "claude-code", "claude_code", "claudecode", "cc" -> CLAUDE_CODE;
            default -> throw new IllegalArgumentException("unknown style: " + raw);
        };
    }
}
