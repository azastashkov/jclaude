package org.jclaude.cli.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * JLine 3-backed line editor for the REPL.
 *
 * <p>Mirrors the Rust {@code LineEditor} from {@code rusty-claude-cli/src/input.rs}:
 *
 * <ul>
 *   <li>Persistent history at {@code ~/.jclaude/history} (one entry per line).
 *   <li>Tab completion via {@link SlashCommandCompleter} (slash commands) plus file-name and
 *       arbitrary-string completers.
 *   <li>Returns a sealed {@link ReadOutcome} so the REPL can distinguish submit / cancel
 *       (Ctrl+C with non-empty buffer) / exit (Ctrl+D or Ctrl+C with empty buffer).
 * </ul>
 */
public final class LineReader implements AutoCloseable {

    /** Default history file location: {@code $HOME/.jclaude/history}. */
    public static Path default_history_path() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = ".";
        }
        return Paths.get(home, ".jclaude", "history");
    }

    private final Terminal terminal;
    private final org.jline.reader.LineReader delegate;
    private final String prompt;
    private final boolean owns_terminal;

    public LineReader(String prompt) throws IOException {
        this(prompt, default_history_path(), default_completions());
    }

    public LineReader(String prompt, Path history_path, List<String> slash_completions) throws IOException {
        this.prompt = prompt;
        this.terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        this.owns_terminal = true;
        this.delegate = build_reader(terminal, history_path, slash_completions);
    }

    /** Test-only ctor that lets us substitute an in-memory terminal. */
    LineReader(String prompt, Terminal terminal, Path history_path, List<String> slash_completions) {
        this.prompt = prompt;
        this.terminal = terminal;
        this.owns_terminal = false;
        this.delegate = build_reader(terminal, history_path, slash_completions);
    }

    private static List<String> default_completions() {
        return SlashCommandCompleter.default_slash_completions();
    }

    @SuppressWarnings("deprecation")
    private static org.jline.reader.LineReader build_reader(
            Terminal terminal, Path history_path, List<String> slash_completions) {
        DefaultHistory history = new DefaultHistory();
        SlashCommandCompleter slash = new SlashCommandCompleter(slash_completions, List.of());
        FileNameCompleter files = new FileNameCompleter();
        AggregateCompleter completer = new AggregateCompleter(slash, files);
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .history(history);
        if (history_path != null) {
            try {
                Files.createDirectories(history_path.getParent());
            } catch (IOException ignored) {
                // history directory creation is best-effort
            }
            builder.variable(org.jline.reader.LineReader.HISTORY_FILE, history_path.toString());
        }
        org.jline.reader.LineReader reader = builder.build();
        // Eagerly attach history so JLine reads any prior file before the first readLine.
        history.attach(reader);
        return reader;
    }

    /** Read a single line. Distinguishes submit, cancel, and exit outcomes. */
    public ReadOutcome read_line() {
        try {
            String line = delegate.readLine(prompt);
            return new ReadOutcome.Submit(line);
        } catch (UserInterruptException interrupt) {
            String partial = interrupt.getPartialLine();
            if (partial != null && !partial.isEmpty()) {
                return new ReadOutcome.Cancel();
            }
            return new ReadOutcome.Exit();
        } catch (EndOfFileException eof) {
            return new ReadOutcome.Exit();
        }
    }

    public void push_history(String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        delegate.getHistory().add(entry);
    }

    public List<String> history_entries() {
        List<String> entries = new ArrayList<>();
        delegate.getHistory().forEach(entry -> entries.add(entry.line()));
        return entries;
    }

    public Terminal terminal() {
        return terminal;
    }

    @Override
    public void close() {
        try {
            delegate.getHistory().save();
        } catch (IOException ignored) {
            // best-effort save on close
        }
        if (owns_terminal) {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
    }

    /** Outcome of a single readline call. */
    public sealed interface ReadOutcome {

        /** A line was submitted by the user. */
        record Submit(String line) implements ReadOutcome {}

        /** Ctrl+C with a non-empty buffer — cancels the in-progress entry. */
        record Cancel() implements ReadOutcome {}

        /** Ctrl+D or Ctrl+C on an empty buffer — caller should leave the loop. */
        record Exit() implements ReadOutcome {}
    }
}
