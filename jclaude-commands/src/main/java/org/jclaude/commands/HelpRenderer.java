package org.jclaude.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders the {@code /help} text and per-command detail strings. Mirrors the
 * Rust render helpers around {@code SLASH_COMMAND_SPECS}.
 */
public final class HelpRenderer {

    private static final String[] CATEGORIES = {"Session", "Tools", "Config", "Debug"};
    private static final Set<String> SESSION = Set.of(
            "help",
            "status",
            "cost",
            "resume",
            "session",
            "version",
            "usage",
            "stats",
            "rename",
            "clear",
            "compact",
            "history",
            "tokens",
            "cache",
            "exit",
            "summary",
            "tag",
            "thinkback",
            "copy",
            "share",
            "feedback",
            "rewind",
            "pin",
            "unpin",
            "bookmarks",
            "context",
            "files",
            "focus",
            "unfocus",
            "retry",
            "stop",
            "undo");
    private static final Set<String> CONFIG = Set.of(
            "model",
            "permissions",
            "config",
            "memory",
            "theme",
            "vim",
            "voice",
            "color",
            "effort",
            "fast",
            "brief",
            "output-style",
            "keybindings",
            "privacy-settings",
            "stickers",
            "language",
            "profile",
            "max-tokens",
            "temperature",
            "system-prompt",
            "api-key",
            "terminal-setup",
            "notifications",
            "telemetry",
            "providers",
            "env",
            "project",
            "reasoning",
            "budget",
            "rate-limit",
            "workspace",
            "reset",
            "ide",
            "desktop",
            "upgrade");
    private static final Set<String> DEBUG =
            Set.of("debug-tool-call", "doctor", "sandbox", "diagnostics", "tool-details", "changelog", "metrics");

    private HelpRenderer() {}

    /** Returns the category label for the given command name, mirroring Rust {@code slash_command_category}. */
    static String slash_command_category(String name) {
        if (SESSION.contains(name)) {
            return "Session";
        }
        if (CONFIG.contains(name)) {
            return "Config";
        }
        if (DEBUG.contains(name)) {
            return "Debug";
        }
        return "Tools";
    }

    private static String slash_command_usage(SlashCommandSpec spec) {
        return spec.argument_hint() != null ? "/" + spec.name() + " " + spec.argument_hint() : "/" + spec.name();
    }

    private static List<String> slash_command_detail_lines(SlashCommandSpec spec) {
        List<String> lines = new ArrayList<>();
        lines.add("/" + spec.name());
        lines.add("  Summary          " + spec.summary());
        lines.add("  Usage            " + slash_command_usage(spec));
        lines.add("  Category         " + slash_command_category(spec.name()));
        if (!spec.aliases().isEmpty()) {
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < spec.aliases().size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append('/').append(spec.aliases().get(i));
            }
            lines.add("  Aliases          " + buf);
        }
        if (spec.resume_supported()) {
            lines.add("  Resume           Supported with --resume SESSION.jsonl");
        }
        return lines;
    }

    /** Returns the per-command detail block, or {@code null} if the command is unknown. */
    public static String render_slash_command_help_detail(String name) {
        SlashCommandSpec spec = SlashCommandSpecs.find_slash_command_spec(name);
        if (spec == null) {
            return null;
        }
        return String.join("\n", slash_command_detail_lines(spec));
    }

    private static String format_slash_command_help_line(SlashCommandSpec spec) {
        String name = slash_command_usage(spec);
        StringBuilder alias_suffix = new StringBuilder();
        if (!spec.aliases().isEmpty()) {
            alias_suffix.append(" (aliases: ");
            for (int i = 0; i < spec.aliases().size(); i++) {
                if (i > 0) {
                    alias_suffix.append(", ");
                }
                alias_suffix.append('/').append(spec.aliases().get(i));
            }
            alias_suffix.append(')');
        }
        String resume = spec.resume_supported() ? " [resume]" : "";
        return String.format("  %-66s %s%s%s", name, spec.summary(), alias_suffix, resume);
    }

    /**
     * Renders the slash-command help section, optionally excluding stub commands. Pass an empty list
     * to include every command. Mirrors Rust {@code render_slash_command_help_filtered}.
     */
    public static String render_slash_command_help_filtered(List<String> exclude) {
        List<String> lines = new ArrayList<>();
        lines.add("Slash commands");
        lines.add("  Start here        /status, /diff, /agents, /skills, /commit");
        lines.add("  [resume]          also works with --resume SESSION.jsonl");
        lines.add("");

        for (String category : CATEGORIES) {
            lines.add(category);
            for (SlashCommandSpec spec : SlashCommandSpecs.slash_command_specs()) {
                if (!slash_command_category(spec.name()).equals(category)) {
                    continue;
                }
                if (exclude != null && exclude.contains(spec.name())) {
                    continue;
                }
                lines.add(format_slash_command_help_line(spec));
            }
            lines.add("");
        }

        return trim_trailing_blank(lines);
    }

    /** Renders the full {@code /help} block including keyboard shortcuts. */
    public static String render_slash_command_help() {
        List<String> lines = new ArrayList<>();
        lines.add("Slash commands");
        lines.add("  Start here        /status, /diff, /agents, /skills, /commit");
        lines.add("  [resume]          also works with --resume SESSION.jsonl");
        lines.add("");

        for (String category : CATEGORIES) {
            lines.add(category);
            for (SlashCommandSpec spec : SlashCommandSpecs.slash_command_specs()) {
                if (!slash_command_category(spec.name()).equals(category)) {
                    continue;
                }
                lines.add(format_slash_command_help_line(spec));
            }
            lines.add("");
        }

        lines.add("Keyboard shortcuts");
        lines.add("  Up/Down              Navigate prompt history");
        lines.add("  Tab                  Complete commands, modes, and recent sessions");
        lines.add("  Ctrl-C               Clear input (or exit on empty prompt)");
        lines.add("  Shift+Enter/Ctrl+J   Insert a newline");

        return trim_trailing_blank(lines);
    }

    private static String trim_trailing_blank(List<String> lines) {
        int last = lines.size();
        while (last > 0 && lines.get(last - 1).isEmpty()) {
            last--;
        }
        return String.join("\n", lines.subList(0, last));
    }
}
