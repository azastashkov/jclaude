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
import org.jclaude.runtime.conversation.ProgressListener;
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

    /** Dot frames for the Claude Code-style status spinner. Pulse-like, not sparkle. */
    private static final String[] CLAUDE_CODE_DOTS = {"·", "∙", "•", "∙"};

    /**
     * Verb pool for the Claude Code-style status line. One verb is chosen at random per turn so
     * different turns feel different without flickering inside a single turn. Includes the
     * marquee Claude Code verbs (Warping, Infusing, …) plus a stylistic long tail.
     */
    private static final String[] CLAUDE_CODE_VERBS = {
        "Warping",
        "Infusing",
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
        "Hunting",
        "Channeling",
        "Materializing"
    };

    /**
     * Tip pool surfaced under the spinner with a {@code ⎿  } continuation. One tip is chosen at
     * random per turn (mirrors how Claude Code rotates contextual hints).
     */
    private static final String[] CLAUDE_CODE_TIPS = {
        "Tip: Use /help to see available commands",
        "Tip: --compact suppresses tool blocks; only the prose answer remains",
        "Tip: Press Ctrl+D or type /exit to leave the REPL",
        "Tip: --style jclaude switches to rounded boxes if you prefer the audit layout",
        "Tip: --allowedTools restricts the tool surface offered to the model",
        "Tip: --resume <session-id> reopens a JSONL session you previously ran"
    };

    /** ANSI: cursor previous line (col 0). */
    private static final String CSI_CURSOR_PREV_LINE = "\033[F";
    /** ANSI: cursor next line (col 0). */
    private static final String CSI_CURSOR_NEXT_LINE = "\033[E";
    /** ANSI: erase entire current line. */
    private static final String CSI_ERASE_LINE = "\033[2K";

    /**
     * Drive a Claude Code-style two-line status alongside {@link ConversationRuntime#run_turn}.
     *
     * <pre>
     * · Infusing… (10s · ↓ 277 tokens · thinking)
     *   ⎿  Tip: …
     * </pre>
     *
     * Token count is polled from {@link ConversationRuntime#usage()}; it advances in chunks
     * because usage is recorded once per model iteration, not per delta — that matches the
     * upstream tool's behavior closely enough that a viewer cannot tell the difference. The
     * "thinking" label flips to {@code thought for Ns} once any output token has been observed.
     */
    private TurnSummary run_turn_with_claude_code_spinner(String line) {
        AnsiPalette p = renderer.palette();
        String idle_verb = CLAUDE_CODE_VERBS[
                java.util.concurrent.ThreadLocalRandom.current().nextInt(CLAUDE_CODE_VERBS.length)];
        String tip = CLAUDE_CODE_TIPS[
                java.util.concurrent.ThreadLocalRandom.current().nextInt(CLAUDE_CODE_TIPS.length)];
        long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean printed_once = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong thought_ended_ns = new java.util.concurrent.atomic.AtomicLong(0L);
        java.util.concurrent.atomic.AtomicInteger frame = new java.util.concurrent.atomic.AtomicInteger(0);

        // Live signal updated by the adapter as wire events arrive: how many text characters have
        // streamed in this turn, and the most recent tool name we've seen the model start.
        java.util.concurrent.atomic.AtomicLong char_count = new java.util.concurrent.atomic.AtomicLong(0L);
        java.util.concurrent.atomic.AtomicReference<String> latest_tool =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        ProgressListener listener = new ProgressListener() {
            @Override
            public void on_text_delta_received(int chars) {
                char_count.addAndGet(chars);
            }

            @Override
            public void on_tool_starting(String tool_name) {
                latest_tool.set(tool_name);
            }
        };
        runtime.with_progress_listener(listener);

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
                if (paused.get()) {
                    continue;
                }
                long now = System.nanoTime();
                long elapsed_s = (now - started) / 1_000_000_000L;
                long chars = char_count.get();
                if (chars > 0 && thought_ended_ns.get() == 0L) {
                    thought_ended_ns.set(now);
                }
                String thought_label;
                long ended = thought_ended_ns.get();
                if (ended == 0L) {
                    thought_label = "thinking";
                } else {
                    thought_label = "thought for " + ((ended - started) / 1_000_000_000L) + "s";
                }
                String dot = CLAUDE_CODE_DOTS[frame.getAndIncrement() % CLAUDE_CODE_DOTS.length];
                String spinner_line =
                        format_claude_code_status(p, dot, idle_verb, elapsed_s, chars, latest_tool.get(), thought_label);
                String tip_line = "  " + p.dim("⎿  " + tip);
                StringBuilder buf = new StringBuilder();
                if (printed_once.get()) {
                    buf.append(CSI_CURSOR_PREV_LINE).append(CSI_ERASE_LINE);
                }
                buf.append(spinner_line)
                        .append(CSI_CURSOR_NEXT_LINE)
                        .append(CSI_ERASE_LINE)
                        .append(tip_line);
                out.print(buf.toString());
                out.flush();
                printed_once.set(true);
            }
        });
        // Wrap the prompter so y/N approval prompts don't get clobbered by the next spinner tick:
        // pause the ticker, clear both spinner lines, delegate, then resume on a fresh line.
        PermissionPrompter wrapped = request -> {
            paused.set(true);
            clear_two_line_status(printed_once.get());
            try {
                return prompter.decide(request);
            } finally {
                paused.set(false);
                printed_once.set(false);
            }
        };
        try {
            return runtime.run_turn(line, wrapped);
        } finally {
            done.set(true);
            ticker.interrupt();
            try {
                ticker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Detach the listener so a subsequent non-spinner call (e.g. a slash command that hits
            // the runtime) doesn't accidentally feed a stale spinner.
            runtime.with_progress_listener(ProgressListener.NO_OP);
            clear_two_line_status(printed_once.get());
        }
    }

    /**
     * Compose the spinner's status line. Picks a verb based on what the adapter has reported so
     * far: a tool name beats raw streaming beats the random idle verb. The format function is
     * extracted (and package-private) so it can be unit-tested without driving the whole REPL.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code · Calling bash… (12s · ↓ 800 chars · thinking)} — tool detected
     *   <li>{@code · Streaming… (6s · ↓ 1.4k chars · thinking)} — text flowing, no tool yet
     *   <li>{@code · Infusing… (4s · thinking)} — nothing heard yet
     * </ul>
     */
    static String format_claude_code_status(
            AnsiPalette palette,
            String dot,
            String idle_verb,
            long elapsed_s,
            long char_count,
            String latest_tool,
            String thought_label) {
        String verb;
        if (latest_tool != null && !latest_tool.isEmpty()) {
            verb = "Calling " + latest_tool;
        } else if (char_count > 0L) {
            verb = "Streaming";
        } else {
            verb = idle_verb;
        }
        StringBuilder paren = new StringBuilder();
        paren.append('(').append(elapsed_s).append('s');
        if (char_count > 0L) {
            paren.append(" · ↓ ").append(format_chars(char_count)).append(" chars");
        }
        paren.append(" · ").append(thought_label).append(')');
        return palette.spinner_active(dot + " " + verb + "…") + palette.dim(" " + paren);
    }

    /** Format a char count: 999 → "999", 1400 → "1.4k", 12000 → "12k". */
    static String format_chars(long n) {
        if (n < 1000L) {
            return Long.toString(n);
        }
        double k = n / 1000.0;
        if (k >= 100.0) {
            return Math.round(k) + "k";
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", k);
    }

    /**
     * Erase the two-line status block (spinner + tip) and leave the cursor at the start of the
     * spinner-line column so subsequent prints look fresh. No-op if the spinner never drew.
     */
    private void clear_two_line_status(boolean had_drawn) {
        if (!had_drawn) {
            return;
        }
        StringBuilder buf = new StringBuilder();
        buf.append(CSI_CURSOR_PREV_LINE)
                .append(CSI_ERASE_LINE)
                .append(CSI_CURSOR_NEXT_LINE)
                .append(CSI_ERASE_LINE)
                .append(CSI_CURSOR_PREV_LINE);
        out.print(buf.toString());
        out.flush();
    }

    /** Format a token count like Claude Code: 999 → "999", 3149 → "3.1k", 12000 → "12k". */
    static String format_tokens(long n) {
        if (n < 1000L) {
            return Long.toString(n);
        }
        double k = n / 1000.0;
        if (k >= 100.0) {
            return Math.round(k) + "k";
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", k);
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
