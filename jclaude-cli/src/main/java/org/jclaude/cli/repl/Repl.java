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
     * Build a Repl using sensible defaults: stdout/stderr, default ANSI palette, the supplied
     * output style, and a fresh {@link LineReader} backed by the user's home history file.
     */
    public static Repl build(
            ConversationRuntime runtime, PermissionPrompter prompter, org.jclaude.cli.OutputStyle style)
            throws IOException {
        AnsiPalette palette = AnsiPalette.DEFAULT;
        TerminalRenderer renderer = new TerminalRenderer(System.out, palette, style);
        LineReader reader = new LineReader("> ");
        return new Repl(runtime, prompter, renderer, reader, System.out, System.err);
    }

    /** Default-style overload preserved for tests / legacy callers. */
    public static Repl build(ConversationRuntime runtime, PermissionPrompter prompter) throws IOException {
        return build(runtime, prompter, org.jclaude.cli.OutputStyle.JCLAUDE);
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

            // Forward as user prompt to the runtime. In claude-code style we drive an animated
            // sparkle spinner with a random verb (matches the Claude Code CLI feel); the default
            // jclaude style runs without a progress indicator since the rounded tool boxes already
            // arrive incrementally.
            try {
                TurnSummary summary;
                if (renderer.style() == org.jclaude.cli.OutputStyle.CLAUDE_CODE) {
                    summary = run_turn_with_claude_code_spinner(line);
                } else {
                    summary = runtime.run_turn(line, prompter);
                }
                renderer.render(summary, false);
            } catch (RuntimeException error) {
                err.println(renderer.palette().red("error: " + error.getMessage()));
                err.flush();
            }
        }
    }

    /** Sparkle frames for the Claude Code-style spinner. */
    private static final String[] CLAUDE_CODE_SPARKLES = {"✻", "✶", "✷", "✸", "✺", "✣", "✤", "✥"};

    /**
     * Verb pool for the Claude Code-style status line. One verb is chosen at random per turn so
     * different turns feel different without flickering inside a single turn. List intentionally
     * includes a mix of energetic + understated words to mirror the upstream tool's vibe.
     */
    private static final String[] CLAUDE_CODE_VERBS = {
        "Tinkering",
        "Cogitating",
        "Pondering",
        "Plotting",
        "Marshaling",
        "Brewing",
        "Concocting",
        "Crafting",
        "Forging",
        "Whisking",
        "Conjuring",
        "Devising",
        "Wrangling",
        "Untangling",
        "Spelunking",
        "Reticulating",
        "Composing",
        "Computing",
        "Working",
        "Hunting"
    };

    /**
     * Drive a Claude Code-style sparkle spinner alongside {@link ConversationRuntime#run_turn}.
     * Format: {@code ✻ Tinkering… (3s)}, sparkle cycling every 120 ms, elapsed seconds updating
     * each tick, verb fixed for the duration of the turn. Cancels cleanly in the finally clause
     * and clears the line before returning so the renderer prints on a clean cursor.
     */
    private TurnSummary run_turn_with_claude_code_spinner(String line) {
        AnsiPalette p = renderer.palette();
        String verb = CLAUDE_CODE_VERBS[
                java.util.concurrent.ThreadLocalRandom.current().nextInt(CLAUDE_CODE_VERBS.length)];
        long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger frame = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread ticker = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    return;
                }
                if (done.get()) {
                    return;
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000_000L;
                String sparkle = CLAUDE_CODE_SPARKLES[frame.getAndIncrement() % CLAUDE_CODE_SPARKLES.length];
                StringBuilder buf = new StringBuilder();
                buf.append('\r')
                        .append(p.spinner_active(sparkle + " " + verb + "…"))
                        .append(p.dim(" (" + elapsed + "s)"))
                        .append("                    "); // pad so longer prior labels are erased
                buf.append('\r')
                        .append(p.spinner_active(sparkle + " " + verb + "…"))
                        .append(p.dim(" (" + elapsed + "s)"));
                out.print(buf.toString());
                out.flush();
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
            // Clear the spinner line before the renderer prints.
            out.print("\r");
            for (int i = 0; i < 64; i++) {
                out.print(' ');
            }
            out.print('\r');
            out.flush();
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
