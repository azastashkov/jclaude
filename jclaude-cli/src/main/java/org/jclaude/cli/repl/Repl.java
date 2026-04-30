package org.jclaude.cli.repl;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import org.jclaude.cli.input.LineReader;
import org.jclaude.cli.render.AnsiPalette;
import org.jclaude.cli.render.TerminalRenderer;
import org.jclaude.commands.SlashCommandDispatcher;
import org.jclaude.commands.SlashCommandResult;
import org.jclaude.runtime.conversation.CompactionConfig;
import org.jclaude.runtime.conversation.ConversationRuntime;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.session.Session;

/**
 * Interactive REPL mode. Triggered when no {@code -p}/{@code --print} flag is supplied and stdin
 * is a TTY.
 *
 * <p>Loop body:
 *
 * <ol>
 *   <li>Read a line via {@link LineReader}.
 *   <li>If the line is blank, ignore.
 *   <li>If the line starts with {@code /}, dispatch it via {@link SlashCommandDispatcher} and
 *       print the textual response (or fall through for runtime-bound commands).
 *   <li>Otherwise, feed it to {@link ConversationRuntime#run_turn} and render the streaming
 *       response via {@link TerminalRenderer}.
 * </ol>
 */
public final class Repl {

    private final ConversationRuntime runtime;
    private final PermissionPrompter prompter;
    private final TerminalRenderer renderer;
    private final LineReader reader;
    private final PrintStream out;
    private final PrintStream err;

    public Repl(
            ConversationRuntime runtime,
            PermissionPrompter prompter,
            TerminalRenderer renderer,
            LineReader reader,
            PrintStream out,
            PrintStream err) {
        this.runtime = runtime;
        this.prompter = prompter;
        this.renderer = renderer;
        this.reader = reader;
        this.out = out;
        this.err = err;
    }

    /**
     * Build a Repl using sensible defaults: stdout/stderr, default ANSI palette, and a fresh
     * {@link LineReader} backed by the user's home history file.
     */
    public static Repl build(ConversationRuntime runtime, PermissionPrompter prompter) throws IOException {
        AnsiPalette palette = AnsiPalette.DEFAULT;
        TerminalRenderer renderer = new TerminalRenderer(System.out, palette);
        LineReader reader = new LineReader("> ");
        return new Repl(runtime, prompter, renderer, reader, System.out, System.err);
    }

    /** Run the REPL until the user issues an exit / EOF. Returns 0 on normal termination. */
    public int run() {
        out.println(renderer.palette().dim("jclaude REPL — type /help for commands, Ctrl+D to exit."));
        out.flush();
        while (true) {
            LineReader.ReadOutcome outcome = reader.read_line();
            if (outcome instanceof LineReader.ReadOutcome.Exit) {
                out.println();
                out.flush();
                return 0;
            }
            if (outcome instanceof LineReader.ReadOutcome.Cancel) {
                out.println(renderer.palette().dim("(cancelled)"));
                continue;
            }
            if (!(outcome instanceof LineReader.ReadOutcome.Submit submit)) {
                continue;
            }
            String line = submit.line();
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            reader.push_history(trimmed);

            if (trimmed.startsWith("/")) {
                if (handle_slash_command(trimmed)) {
                    return 0;
                }
                continue;
            }

            // Forward as user prompt to the runtime, with a spinner that ticks while the turn is
            // in flight (model streaming + tool dispatches). Mirrors the Claude Code CLI feel.
            try {
                TurnSummary summary = run_turn_with_spinner(line);
                renderer.render(summary, false);
            } catch (RuntimeException error) {
                err.println(renderer.palette().red("error: " + error.getMessage()));
                err.flush();
            }
        }
    }

    /**
     * Run one turn with an animated spinner. The spinner ticks every 100&nbsp;ms on a background
     * virtual thread, displaying elapsed time alongside the rotating glyph; it stops cleanly
     * before the renderer prints results so the line is clear.
     *
     * <p>Any exception from {@code runtime.run_turn} is allowed to propagate after the spinner is
     * cancelled, so the caller's existing error path is unchanged.
     */
    private TurnSummary run_turn_with_spinner(String line) {
        org.jclaude.cli.render.Spinner spinner = new org.jclaude.cli.render.Spinner(out, renderer.palette());
        long started = System.nanoTime();
        spinner.start("Working");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread ticker = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                if (done.get()) {
                    return;
                }
                long elapsed_seconds = (System.nanoTime() - started) / 1_000_000_000L;
                spinner.start("Working (" + elapsed_seconds + "s)");
            }
        });
        try {
            return runtime.run_turn(line, prompter);
        } finally {
            done.set(true);
            ticker.interrupt();
            try {
                ticker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            spinner.stop();
        }
    }

    /**
     * Dispatch a {@code /command}. Returns {@code true} when the command requested REPL exit
     * ({@code /exit}), {@code false} otherwise.
     */
    private boolean handle_slash_command(String input) {
        if (input.equals("/exit") || input.equals("/quit")) {
            return true;
        }
        Optional<SlashCommandResult> result =
                SlashCommandDispatcher.handle_slash_command(input, runtime.session(), CompactionConfig.defaults());
        if (result.isEmpty()) {
            // Runtime-bound or unsupported command — surface a helpful note.
            out.println(renderer.palette().dim("(slash command '" + input + "' has no REPL handler in MVP)"));
            out.flush();
            return false;
        }
        SlashCommandResult sr = result.get();
        // Adopt any session mutation produced by the command.
        if (sr.session() != null) {
            adopt_session_if_changed(sr.session());
        }
        out.println(sr.message());
        out.flush();
        return false;
    }

    private void adopt_session_if_changed(Session session) {
        // The runtime owns its session; for now we only echo the command result. A future commit
        // can replace the runtime's session reference once that API is exposed publicly.
    }
}
