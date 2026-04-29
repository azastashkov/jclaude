package org.jclaude.commands;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parser for slash-command input. Mirrors the Rust {@code
 * validate_slash_command_input} function and its helper cluster.
 */
final class SlashCommandParser {

    private static final Set<String> PERMISSION_MODES = Set.of("read-only", "workspace-write", "danger-full-access");
    private static final Set<String> CONFIG_SECTIONS = Set.of("env", "hooks", "model", "plugins");

    private SlashCommandParser() {}

    static Optional<SlashCommand> validate_slash_command_input(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }
        String body = trimmed.substring(1);
        String[] parts = body.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new SlashCommandParseError(
                    "Slash command name is missing. Use /help to list available slash commands.");
        }
        String command = parts[0];
        String[] argsArray = new String[parts.length - 1];
        System.arraycopy(parts, 1, argsArray, 0, argsArray.length);
        List<String> args = List.of(argsArray);
        String remainder = remainder_after_command(trimmed, command);

        SlashCommand parsed = parse_command(command, args, remainder);
        return Optional.of(parsed);
    }

    private static SlashCommand parse_command(String command, List<String> args, String remainder) {
        return switch (command) {
            case "help" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Help();
            }
            case "status" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Status();
            }
            case "sandbox" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Sandbox();
            }
            case "compact" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Compact();
            }
            case "bughunter" -> new SlashCommand.Bughunter(remainder);
            case "commit" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Commit();
            }
            case "pr" -> new SlashCommand.Pr(remainder);
            case "issue" -> new SlashCommand.Issue(remainder);
            case "ultraplan" -> new SlashCommand.Ultraplan(remainder);
            case "teleport" -> new SlashCommand.Teleport(require_remainder(command, remainder, "<symbol-or-path>"));
            case "debug-tool-call" -> {
                validate_no_args(command, args);
                yield new SlashCommand.DebugToolCall();
            }
            case "model" -> new SlashCommand.Model(optional_single_arg(command, args, "[model]"));
            case "permissions" -> new SlashCommand.Permissions(parse_permissions_mode(args));
            case "clear" -> new SlashCommand.Clear(parse_clear_args(args));
            case "cost" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Cost();
            }
            case "resume" -> new SlashCommand.Resume(require_remainder(command, remainder, "<session-path>"));
            case "config" -> new SlashCommand.Config(parse_config_section(args));
            case "mcp" -> parse_mcp_command(args);
            case "memory" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Memory();
            }
            case "init" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Init();
            }
            case "diff" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Diff();
            }
            case "version" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Version();
            }
            case "export" -> new SlashCommand.Export(remainder);
            case "session" -> parse_session_command(args);
            case "plugin", "plugins", "marketplace" -> parse_plugin_command(args);
            case "agents" -> new SlashCommand.Agents(parse_list_or_help_args(command, remainder));
            case "skills", "skill" -> new SlashCommand.Skills(parse_skills_args(remainder));
            case "doctor", "providers" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Doctor();
            }
            case "login", "logout" -> throw command_error(
                    "This auth flow was removed. Set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN instead.", command, "");
            case "vim" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Vim();
            }
            case "upgrade" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Upgrade();
            }
            case "stats", "tokens", "cache" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Stats();
            }
            case "share" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Share();
            }
            case "feedback" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Feedback();
            }
            case "files" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Files();
            }
            case "fast" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Fast();
            }
            case "exit" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Exit();
            }
            case "summary" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Summary();
            }
            case "desktop" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Desktop();
            }
            case "brief" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Brief();
            }
            case "advisor" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Advisor();
            }
            case "stickers" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Stickers();
            }
            case "insights" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Insights();
            }
            case "thinkback" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Thinkback();
            }
            case "release-notes" -> {
                validate_no_args(command, args);
                yield new SlashCommand.ReleaseNotes();
            }
            case "security-review" -> {
                validate_no_args(command, args);
                yield new SlashCommand.SecurityReview();
            }
            case "keybindings" -> {
                validate_no_args(command, args);
                yield new SlashCommand.Keybindings();
            }
            case "privacy-settings" -> {
                validate_no_args(command, args);
                yield new SlashCommand.PrivacySettings();
            }
            case "plan" -> new SlashCommand.Plan(remainder);
            case "review" -> new SlashCommand.Review(remainder);
            case "tasks" -> new SlashCommand.Tasks(remainder);
            case "theme" -> new SlashCommand.Theme(remainder);
            case "voice" -> new SlashCommand.Voice(remainder);
            case "usage" -> new SlashCommand.Usage(remainder);
            case "rename" -> new SlashCommand.Rename(remainder);
            case "copy" -> new SlashCommand.Copy(remainder);
            case "hooks" -> new SlashCommand.Hooks(remainder);
            case "context" -> new SlashCommand.Context(remainder);
            case "color" -> new SlashCommand.Color(remainder);
            case "effort" -> new SlashCommand.Effort(remainder);
            case "branch" -> new SlashCommand.Branch(remainder);
            case "rewind" -> new SlashCommand.Rewind(remainder);
            case "ide" -> new SlashCommand.Ide(remainder);
            case "tag" -> new SlashCommand.Tag(remainder);
            case "output-style" -> new SlashCommand.OutputStyle(remainder);
            case "add-dir" -> new SlashCommand.AddDir(remainder);
            case "history" -> new SlashCommand.History(optional_single_arg(command, args, "[count]"));
            default -> new SlashCommand.Unknown(command);
        };
    }

    private static void validate_no_args(String command, List<String> args) {
        if (args.isEmpty()) {
            return;
        }
        throw command_error("Unexpected arguments for /" + command + ".", command, "/" + command);
    }

    private static String optional_single_arg(String command, List<String> args, String argument_hint) {
        return switch (args.size()) {
            case 0 -> null;
            case 1 -> args.get(0);
            default -> throw usage_error(command, argument_hint);
        };
    }

    private static String require_remainder(String command, String remainder, String argument_hint) {
        if (remainder == null) {
            throw usage_error(command, argument_hint);
        }
        return remainder;
    }

    private static String parse_permissions_mode(List<String> args) {
        String mode = optional_single_arg("permissions", args, "[read-only|workspace-write|danger-full-access]");
        if (mode == null) {
            return null;
        }
        if (PERMISSION_MODES.contains(mode)) {
            return mode;
        }
        throw command_error(
                "Unsupported /permissions mode '" + mode + "'. Use read-only, workspace-write, or danger-full-access.",
                "permissions",
                "/permissions [read-only|workspace-write|danger-full-access]");
    }

    private static boolean parse_clear_args(List<String> args) {
        return switch (args.size()) {
            case 0 -> false;
            case 1 -> {
                String only = args.get(0);
                if ("--confirm".equals(only)) {
                    yield true;
                }
                throw command_error(
                        "Unsupported /clear argument '" + only + "'. Use /clear or /clear --confirm.",
                        "clear",
                        "/clear [--confirm]");
            }
            default -> throw usage_error("clear", "[--confirm]");
        };
    }

    private static String parse_config_section(List<String> args) {
        String section = optional_single_arg("config", args, "[env|hooks|model|plugins]");
        if (section == null) {
            return null;
        }
        if (CONFIG_SECTIONS.contains(section)) {
            return section;
        }
        throw command_error(
                "Unsupported /config section '" + section + "'. Use env, hooks, model, or plugins.",
                "config",
                "/config [env|hooks|model|plugins]");
    }

    private static SlashCommand parse_session_command(List<String> args) {
        if (args.isEmpty()) {
            return new SlashCommand.Session(null, null);
        }
        String first = args.get(0);
        switch (first) {
            case "list" -> {
                if (args.size() == 1) {
                    return new SlashCommand.Session("list", null);
                }
                throw usage_error(
                        "session", "[list|switch <session-id>|fork [branch-name]|delete <session-id> [--force]]");
            }
            case "switch" -> {
                if (args.size() == 1) {
                    throw usage_error("session switch", "<session-id>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Session("switch", args.get(1));
                }
                throw command_error(
                        "Unexpected arguments for /session switch.", "session", "/session switch <session-id>");
            }
            case "fork" -> {
                if (args.size() == 1) {
                    return new SlashCommand.Session("fork", null);
                }
                if (args.size() == 2) {
                    return new SlashCommand.Session("fork", args.get(1));
                }
                throw command_error(
                        "Unexpected arguments for /session fork.", "session", "/session fork [branch-name]");
            }
            case "delete" -> {
                if (args.size() == 1) {
                    throw usage_error("session delete", "<session-id> [--force]");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Session("delete", args.get(1));
                }
                if (args.size() == 3 && "--force".equals(args.get(2))) {
                    return new SlashCommand.Session("delete-force", args.get(1));
                }
                if (args.size() == 3) {
                    throw command_error(
                            "Unsupported /session delete flag '" + args.get(2) + "'. Use --force to skip confirmation.",
                            "session",
                            "/session delete <session-id> [--force]");
                }
                throw command_error(
                        "Unexpected arguments for /session delete.",
                        "session",
                        "/session delete <session-id> [--force]");
            }
            default -> throw command_error(
                    "Unknown /session action '" + first
                            + "'. Use list, switch <session-id>, fork [branch-name], or delete <session-id> [--force].",
                    "session",
                    "/session [list|switch <session-id>|fork [branch-name]|delete <session-id> [--force]]");
        }
    }

    private static SlashCommand parse_mcp_command(List<String> args) {
        if (args.isEmpty()) {
            return new SlashCommand.Mcp(null, null);
        }
        String first = args.get(0);
        switch (first) {
            case "list" -> {
                if (args.size() == 1) {
                    return new SlashCommand.Mcp("list", null);
                }
                throw usage_error("mcp list", "");
            }
            case "show" -> {
                if (args.size() == 1) {
                    throw usage_error("mcp show", "<server>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Mcp("show", args.get(1));
                }
                throw command_error("Unexpected arguments for /mcp show.", "mcp", "/mcp show <server>");
            }
            case "help", "-h", "--help" -> {
                return new SlashCommand.Mcp("help", null);
            }
            default -> throw command_error(
                    "Unknown /mcp action '" + first + "'. Use list, show <server>, or help.",
                    "mcp",
                    "/mcp [list|show <server>|help]");
        }
    }

    private static SlashCommand parse_plugin_command(List<String> args) {
        if (args.isEmpty()) {
            return new SlashCommand.Plugins(null, null);
        }
        String action = args.get(0);
        switch (action) {
            case "list" -> {
                if (args.size() == 1) {
                    return new SlashCommand.Plugins("list", null);
                }
                throw usage_error("plugin list", "");
            }
            case "install" -> {
                if (args.size() == 1) {
                    throw usage_error("plugin install", "<path>");
                }
                StringBuilder builder = new StringBuilder(args.get(1));
                for (int i = 2; i < args.size(); i++) {
                    builder.append(' ').append(args.get(i));
                }
                return new SlashCommand.Plugins("install", builder.toString());
            }
            case "enable" -> {
                if (args.size() == 1) {
                    throw usage_error("plugin enable", "<name>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Plugins("enable", args.get(1));
                }
                throw command_error("Unexpected arguments for /plugin enable.", "plugin", "/plugin enable <name>");
            }
            case "disable" -> {
                if (args.size() == 1) {
                    throw usage_error("plugin disable", "<name>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Plugins("disable", args.get(1));
                }
                throw command_error("Unexpected arguments for /plugin disable.", "plugin", "/plugin disable <name>");
            }
            case "uninstall" -> {
                if (args.size() == 1) {
                    throw usage_error("plugin uninstall", "<id>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Plugins("uninstall", args.get(1));
                }
                throw command_error("Unexpected arguments for /plugin uninstall.", "plugin", "/plugin uninstall <id>");
            }
            case "update" -> {
                if (args.size() == 1) {
                    throw usage_error("plugin update", "<id>");
                }
                if (args.size() == 2) {
                    return new SlashCommand.Plugins("update", args.get(1));
                }
                throw command_error("Unexpected arguments for /plugin update.", "plugin", "/plugin update <id>");
            }
            default -> throw command_error(
                    "Unknown /plugin action '" + action
                            + "'. Use list, install <path>, enable <name>, disable <name>, uninstall <id>, or update <id>.",
                    "plugin",
                    "/plugin [list|install <path>|enable <name>|disable <name>|uninstall <id>|update <id>]");
        }
    }

    private static String parse_list_or_help_args(String command, String args) {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized == null) {
            return null;
        }
        if (normalized.equals("list")
                || normalized.equals("help")
                || normalized.equals("-h")
                || normalized.equals("--help")) {
            return args;
        }
        throw command_error(
                "Unexpected arguments for /" + command + ": " + normalized + ". Use /" + command + ", /" + command
                        + " list, or /" + command + " help.",
                command,
                "/" + command + " [list|help]");
    }

    private static String parse_skills_args(String args) {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized == null) {
            return null;
        }
        if (normalized.equals("list")
                || normalized.equals("help")
                || normalized.equals("-h")
                || normalized.equals("--help")) {
            return normalized;
        }
        if (normalized.equals("install")) {
            throw command_error("Usage: /skills install <path>", "skills", "/skills install <path>");
        }
        if (normalized.startsWith("install")) {
            String target = normalized.substring("install".length()).trim();
            if (!target.isEmpty()) {
                return "install " + target;
            }
        }
        return normalized;
    }

    private static SlashCommandParseError usage_error(String command, String argument_hint) {
        String usage = ("/" + command + " " + argument_hint).replaceAll("\\s+$", "");
        return command_error("Usage: " + usage, command_root_name(command), usage);
    }

    static SlashCommandParseError command_error(String message, String command, String usage) {
        String detail = HelpRenderer.render_slash_command_help_detail(command);
        String suffix = detail == null ? "" : "\n\n" + detail;
        return new SlashCommandParseError(message + "\n  Usage            " + usage + suffix);
    }

    private static String remainder_after_command(String input, String command) {
        String trimmed = input.trim();
        String prefix = "/" + command;
        if (!trimmed.startsWith(prefix)) {
            return null;
        }
        String tail = trimmed.substring(prefix.length()).trim();
        return tail.isEmpty() ? null : tail;
    }

    private static String command_root_name(String command) {
        int spaceIdx = command.indexOf(' ');
        return spaceIdx < 0 ? command : command.substring(0, spaceIdx);
    }
}
