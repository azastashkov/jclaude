package org.jclaude.commands;

import java.util.List;

/**
 * Static registry of every slash command spec the harness recognises.
 *
 * <p>This is a verbatim port of the Rust {@code SLASH_COMMAND_SPECS} table:
 * order, names, summaries, argument hints, aliases, and {@code resume_supported}
 * flag are preserved byte-for-byte.
 */
public final class SlashCommandSpecs {

    private static final List<SlashCommandSpec> SPECS = List.of(
            SlashCommandSpec.of("help", "Show available slash commands", null, true),
            SlashCommandSpec.of("status", "Show current session status", null, true),
            SlashCommandSpec.of("sandbox", "Show sandbox isolation status", null, true),
            SlashCommandSpec.of("compact", "Compact local session history", null, true),
            SlashCommandSpec.of("model", "Show or switch the active model", "[model]", false),
            SlashCommandSpec.of(
                    "permissions",
                    "Show or switch the active permission mode",
                    "[read-only|workspace-write|danger-full-access]",
                    false),
            SlashCommandSpec.of("clear", "Start a fresh local session", "[--confirm]", true),
            SlashCommandSpec.of("cost", "Show cumulative token usage for this session", null, true),
            SlashCommandSpec.of("resume", "Load a saved session into the REPL", "<session-path>", false),
            SlashCommandSpec.of(
                    "config", "Inspect Claude config files or merged sections", "[env|hooks|model|plugins]", true),
            SlashCommandSpec.of("mcp", "Inspect configured MCP servers", "[list|show <server>|help]", true),
            SlashCommandSpec.of("memory", "Inspect loaded Claude instruction memory files", null, true),
            SlashCommandSpec.of("init", "Create a starter CLAUDE.md for this repo", null, true),
            SlashCommandSpec.of("diff", "Show git diff for current workspace changes", null, true),
            SlashCommandSpec.of("version", "Show CLI version and build information", null, true),
            SlashCommandSpec.of("bughunter", "Inspect the codebase for likely bugs", "[scope]", false),
            SlashCommandSpec.of("commit", "Generate a commit message and create a git commit", null, false),
            SlashCommandSpec.of("pr", "Draft or create a pull request from the conversation", "[context]", false),
            SlashCommandSpec.of("issue", "Draft or create a GitHub issue from the conversation", "[context]", false),
            SlashCommandSpec.of("ultraplan", "Run a deep planning prompt with multi-step reasoning", "[task]", false),
            SlashCommandSpec.of(
                    "teleport", "Jump to a file or symbol by searching the workspace", "<symbol-or-path>", false),
            SlashCommandSpec.of("debug-tool-call", "Replay the last tool call with debug details", null, false),
            SlashCommandSpec.of("export", "Export the current conversation to a file", "[file]", true),
            SlashCommandSpec.of(
                    "session",
                    "List, switch, fork, or delete managed local sessions",
                    "[list|switch <session-id>|fork [branch-name]|delete <session-id> [--force]]",
                    false),
            SlashCommandSpec.withAliases(
                    "plugin",
                    List.of("plugins", "marketplace"),
                    "Manage Claw Code plugins",
                    "[list|install <path>|enable <name>|disable <name>|uninstall <id>|update <id>]",
                    false),
            SlashCommandSpec.of("agents", "List configured agents", "[list|help]", true),
            SlashCommandSpec.withAliases(
                    "skills",
                    List.of("skill"),
                    "List, install, or invoke available skills",
                    "[list|install <path>|help|<skill> [args]]",
                    true),
            SlashCommandSpec.of("doctor", "Diagnose setup issues and environment health", null, true),
            SlashCommandSpec.of("plan", "Toggle or inspect planning mode", "[on|off]", true),
            SlashCommandSpec.of("review", "Run a code review on current changes", "[scope]", false),
            SlashCommandSpec.of("tasks", "List and manage background tasks", "[list|get <id>|stop <id>]", true),
            SlashCommandSpec.of("theme", "Switch the terminal color theme", "[theme-name]", true),
            SlashCommandSpec.of("vim", "Toggle vim keybinding mode", null, true),
            SlashCommandSpec.of("voice", "Toggle voice input mode", "[on|off]", false),
            SlashCommandSpec.of("upgrade", "Check for and install CLI updates", null, false),
            SlashCommandSpec.of("usage", "Show detailed API usage statistics", null, true),
            SlashCommandSpec.of("stats", "Show workspace and session statistics", null, true),
            SlashCommandSpec.of("rename", "Rename the current session", "<name>", false),
            SlashCommandSpec.of("copy", "Copy conversation or output to clipboard", "[last|all]", true),
            SlashCommandSpec.of("share", "Share the current conversation", null, false),
            SlashCommandSpec.of("feedback", "Submit feedback about the current session", null, false),
            SlashCommandSpec.of("hooks", "List and manage lifecycle hooks", "[list|run <hook>]", true),
            SlashCommandSpec.of("files", "List files in the current context window", null, true),
            SlashCommandSpec.of("context", "Inspect or manage the conversation context", "[show|clear]", true),
            SlashCommandSpec.of("color", "Configure terminal color settings", "[scheme]", true),
            SlashCommandSpec.of("effort", "Set the effort level for responses", "[low|medium|high]", true),
            SlashCommandSpec.of("fast", "Toggle fast/concise response mode", null, true),
            SlashCommandSpec.of("exit", "Exit the REPL session", null, false),
            SlashCommandSpec.of("branch", "Create or switch git branches", "[name]", false),
            SlashCommandSpec.of("rewind", "Rewind the conversation to a previous state", "[steps]", false),
            SlashCommandSpec.of("summary", "Generate a summary of the conversation", null, true),
            SlashCommandSpec.of("desktop", "Open or manage the desktop app integration", null, false),
            SlashCommandSpec.of("ide", "Open or configure IDE integration", "[vscode|cursor]", false),
            SlashCommandSpec.of("tag", "Tag the current conversation point", "[label]", true),
            SlashCommandSpec.of("brief", "Toggle brief output mode", null, true),
            SlashCommandSpec.of("advisor", "Toggle advisor mode for guidance-only responses", null, true),
            SlashCommandSpec.of("stickers", "Browse and manage sticker packs", null, true),
            SlashCommandSpec.of("insights", "Show AI-generated insights about the session", null, true),
            SlashCommandSpec.of("thinkback", "Replay the thinking process of the last response", null, true),
            SlashCommandSpec.of("release-notes", "Generate release notes from recent changes", null, false),
            SlashCommandSpec.of("security-review", "Run a security review on the codebase", "[scope]", false),
            SlashCommandSpec.of("keybindings", "Show or configure keyboard shortcuts", null, true),
            SlashCommandSpec.of("privacy-settings", "View or modify privacy settings", null, true),
            SlashCommandSpec.of("output-style", "Switch output formatting style", "[style]", true),
            SlashCommandSpec.of("add-dir", "Add an additional directory to the context", "<path>", false),
            SlashCommandSpec.of(
                    "allowed-tools", "Show or modify the allowed tools list", "[add|remove|list] [tool]", true),
            SlashCommandSpec.of("api-key", "Show or set the Anthropic API key", "[key]", false),
            SlashCommandSpec.withAliases(
                    "approve", List.of("yes", "y"), "Approve a pending tool execution", null, false),
            SlashCommandSpec.withAliases("deny", List.of("no", "n"), "Deny a pending tool execution", null, false),
            SlashCommandSpec.of("undo", "Undo the last file write or edit", null, false),
            SlashCommandSpec.of("stop", "Stop the current generation", null, false),
            SlashCommandSpec.of("retry", "Retry the last failed message", null, false),
            SlashCommandSpec.of("paste", "Paste clipboard content as input", null, false),
            SlashCommandSpec.of("screenshot", "Take a screenshot and add to conversation", null, false),
            SlashCommandSpec.of("image", "Add an image file to the conversation", "<path>", false),
            SlashCommandSpec.of("terminal-setup", "Configure terminal integration settings", null, true),
            SlashCommandSpec.of("search", "Search files in the workspace", "<query>", false),
            SlashCommandSpec.of("listen", "Listen for voice input", null, false),
            SlashCommandSpec.of("speak", "Read the last response aloud", null, false),
            SlashCommandSpec.of("language", "Set the interface language", "[language]", true),
            SlashCommandSpec.of("profile", "Show or switch user profile", "[name]", false),
            SlashCommandSpec.of("max-tokens", "Show or set the max output tokens", "[count]", true),
            SlashCommandSpec.of("temperature", "Show or set the sampling temperature", "[value]", true),
            SlashCommandSpec.of("system-prompt", "Show the active system prompt", null, true),
            SlashCommandSpec.of("tool-details", "Show detailed info about a specific tool", "<tool-name>", true),
            SlashCommandSpec.of(
                    "format", "Format the last response in a different style", "[markdown|plain|json]", false),
            SlashCommandSpec.of("pin", "Pin a message to persist across compaction", "[message-index]", false),
            SlashCommandSpec.of("unpin", "Unpin a previously pinned message", "[message-index]", false),
            SlashCommandSpec.of("bookmarks", "List or manage conversation bookmarks", "[add|remove|list]", true),
            SlashCommandSpec.withAliases(
                    "workspace", List.of("cwd"), "Show or change the working directory", "[path]", true),
            SlashCommandSpec.of("history", "Show conversation history summary", "[count]", true),
            SlashCommandSpec.of("tokens", "Show token count for the current conversation", null, true),
            SlashCommandSpec.of("cache", "Show prompt cache statistics", null, true),
            SlashCommandSpec.of("providers", "List available model providers", null, true),
            SlashCommandSpec.of("notifications", "Show or configure notification settings", "[on|off|status]", true),
            SlashCommandSpec.of("changelog", "Show recent changes to the codebase", "[count]", true),
            SlashCommandSpec.of("test", "Run tests for the current project", "[filter]", false),
            SlashCommandSpec.of("lint", "Run linting for the current project", "[filter]", false),
            SlashCommandSpec.of("build", "Build the current project", "[target]", false),
            SlashCommandSpec.of("run", "Run a command in the project context", "<command>", false),
            SlashCommandSpec.of("git", "Run a git command in the workspace", "<subcommand>", false),
            SlashCommandSpec.of("stash", "Stash or unstash workspace changes", "[pop|list|apply]", false),
            SlashCommandSpec.of("blame", "Show git blame for a file", "<file> [line]", true),
            SlashCommandSpec.of("log", "Show git log for the workspace", "[count]", true),
            SlashCommandSpec.of("cron", "Manage scheduled tasks", "[list|add|remove]", true),
            SlashCommandSpec.of("team", "Manage agent teams", "[list|create|delete]", true),
            SlashCommandSpec.of("benchmark", "Run performance benchmarks", "[suite]", false),
            SlashCommandSpec.of("migrate", "Run pending data migrations", null, false),
            SlashCommandSpec.of("reset", "Reset configuration to defaults", "[section]", false),
            SlashCommandSpec.of("telemetry", "Show or configure telemetry settings", "[on|off|status]", true),
            SlashCommandSpec.of("env", "Show environment variables visible to tools", null, true),
            SlashCommandSpec.of("project", "Show project detection info", null, true),
            SlashCommandSpec.of("templates", "List or apply prompt templates", "[list|apply <name>]", false),
            SlashCommandSpec.of("explain", "Explain a file or code snippet", "<path> [line-range]", false),
            SlashCommandSpec.of("refactor", "Suggest refactoring for a file or function", "<path> [scope]", false),
            SlashCommandSpec.of("docs", "Generate or show documentation", "[path]", false),
            SlashCommandSpec.of("fix", "Fix errors in a file or project", "[path]", false),
            SlashCommandSpec.of("perf", "Analyze performance of a function or file", "<path>", false),
            SlashCommandSpec.of("chat", "Switch to free-form chat mode", null, false),
            SlashCommandSpec.of("focus", "Focus context on specific files or directories", "<path> [path...]", false),
            SlashCommandSpec.of("unfocus", "Remove focus from files or directories", "[path...]", false),
            SlashCommandSpec.of("web", "Fetch and summarize a web page", "<url>", false),
            SlashCommandSpec.of("map", "Show a visual map of the codebase structure", "[depth]", true),
            SlashCommandSpec.of("symbols", "List symbols (functions, classes, etc.) in a file", "<path>", true),
            SlashCommandSpec.of("references", "Find all references to a symbol", "<symbol>", false),
            SlashCommandSpec.of("definition", "Go to the definition of a symbol", "<symbol>", false),
            SlashCommandSpec.of("hover", "Show hover information for a symbol", "<symbol>", true),
            SlashCommandSpec.of("diagnostics", "Show LSP diagnostics for a file", "[path]", true),
            SlashCommandSpec.of("autofix", "Auto-fix all fixable diagnostics", "[path]", false),
            SlashCommandSpec.of("multi", "Execute multiple slash commands in sequence", "<commands>", false),
            SlashCommandSpec.of("macro", "Record or replay command macros", "[record|stop|play <name>]", false),
            SlashCommandSpec.of("alias", "Create a command alias", "<name> <command>", true),
            SlashCommandSpec.of("parallel", "Run commands in parallel subagents", "<count> <prompt>", false),
            SlashCommandSpec.of("agent", "Manage sub-agents and spawned sessions", "[list|spawn|kill]", true),
            SlashCommandSpec.of(
                    "subagent", "Control active subagent execution", "[list|steer <target> <msg>|kill <id>]", true),
            SlashCommandSpec.of("reasoning", "Toggle extended reasoning mode", "[on|off|stream]", true),
            SlashCommandSpec.of("budget", "Show or set token budget limits", "[show|set <limit>]", true),
            SlashCommandSpec.of("rate-limit", "Configure API rate limiting", "[status|set <rpm>]", true),
            SlashCommandSpec.of("metrics", "Show performance and usage metrics", null, true));

    private SlashCommandSpecs() {}

    /** Returns the immutable list of all known slash-command specs. */
    public static List<SlashCommandSpec> slash_command_specs() {
        return SPECS;
    }

    /** Returns the subset of specs that support {@code --resume SESSION.jsonl}. */
    public static List<SlashCommandSpec> resume_supported_slash_commands() {
        return SPECS.stream().filter(SlashCommandSpec::resume_supported).toList();
    }

    /** Looks up a spec by name or alias (ASCII case-insensitive). */
    static SlashCommandSpec find_slash_command_spec(String name) {
        for (SlashCommandSpec spec : SPECS) {
            if (spec.name().equalsIgnoreCase(name)) {
                return spec;
            }
            for (String alias : spec.aliases()) {
                if (alias.equalsIgnoreCase(name)) {
                    return spec;
                }
            }
        }
        return null;
    }
}
