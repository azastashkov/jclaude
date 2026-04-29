package org.jclaude.cli;

import picocli.CommandLine;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        // When no -p / --print flag is supplied AND stdin is a TTY, the optional REPL entrypoint
        // (org.jclaude.cli.repl.ReplEntrypoint) will claim the invocation; otherwise the existing
        // Picocli one-shot execution path runs. We use reflection so that this Main file does not
        // hard-bind to ReplEntrypoint, keeping subcommand-handlers free to evolve JclaudeCommand
        // without conflicting with this orchestrator.
        if (try_enter_repl(args)) {
            return;
        }
        int rc = new CommandLine(new JclaudeCommand()).execute(args);
        System.exit(rc);
    }

    /**
     * Attempts to delegate to the optional {@code org.jclaude.cli.repl.ReplEntrypoint} class via
     * reflection. Returns {@code true} when the entrypoint claimed the invocation (and exited the
     * JVM); {@code false} when the entrypoint is absent or declined.
     */
    private static boolean try_enter_repl(String[] args) {
        try {
            Class<?> entry = Class.forName("org.jclaude.cli.repl.ReplEntrypoint");
            java.lang.reflect.Method should = entry.getMethod("should_enter_repl", String[].class);
            Object should_result = should.invoke(null, (Object) args);
            if (should_result instanceof Boolean b && b) {
                java.lang.reflect.Method run = entry.getMethod("run", String[].class);
                Object rc = run.invoke(null, (Object) args);
                if (rc instanceof Integer i) {
                    System.exit(i);
                    return true;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // REPL entrypoint not installed — proceed with the Picocli path.
        } catch (ReflectiveOperationException ignored) {
            // REPL entrypoint malformed — proceed with the Picocli path.
        }
        return false;
    }
}
