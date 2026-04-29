package org.jclaude.cli.repl;

/**
 * Decides whether the CLI should enter REPL mode and exposes the entry point.
 *
 * <p>The REPL is selected when:
 *
 * <ul>
 *   <li>No {@code -p}/{@code --print} flag is present, and
 *   <li>No positional prompt arguments are supplied, and
 *   <li>Stdin is attached to a TTY (i.e. {@link System#console()} is non-{@code null}).
 * </ul>
 *
 * <p>This detection runs before Picocli parses any flags so the {@code Main} class can switch
 * between modes without modifying {@code JclaudeCommand} (which is being touched by other agents).
 * Help/version flags are intentionally still routed to Picocli.
 */
public final class ReplEntrypoint {

    private ReplEntrypoint() {}

    public static boolean should_enter_repl(String[] args) {
        // Don't enter REPL when stdin is non-TTY — that breaks integration tests that pipe
        // stdin/stdout across processes.
        if (System.console() == null) {
            return false;
        }
        if (args == null) {
            return true;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg.equals("-p") || arg.equals("--print") || arg.startsWith("-p=") || arg.startsWith("--print=")) {
                return false;
            }
            if (arg.equals("--help") || arg.equals("-h") || arg.equals("--version") || arg.equals("-V")) {
                return false;
            }
        }
        // If any positional argument exists (after stripping flags), assume one-shot mode.
        boolean has_positional = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("-")) {
                // Skip the value if this flag takes one; we don't have full Picocli context here.
                if (takes_value(arg) && i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            has_positional = true;
            break;
        }
        return !has_positional;
    }

    private static boolean takes_value(String flag) {
        return switch (flag) {
            case "--model",
                    "--output-format",
                    "--permission-mode",
                    "--allowedTools",
                    "--resume",
                    "--max-tokens" -> true;
            default -> flag.startsWith("--") && !flag.contains("=");
        };
    }

    /** Run the REPL. Returns the process exit code. */
    /**
     * Run the REPL. Parses {@code args} into a {@link org.jclaude.cli.JclaudeCommand} via Picocli,
     * builds the same runtime pipeline used by one-shot mode, and drives the
     * {@link org.jclaude.cli.repl.Repl} loop.
     */
    public static int run(String[] args) {
        org.jclaude.cli.JclaudeCommand command = new org.jclaude.cli.JclaudeCommand();
        try {
            new picocli.CommandLine(command).parseArgs(args == null ? new String[0] : args);
        } catch (picocli.CommandLine.ParameterException error) {
            System.err.println("error: " + error.getMessage());
            return 2;
        }
        return new org.jclaude.cli.WireRunner(command).repl();
    }
}
