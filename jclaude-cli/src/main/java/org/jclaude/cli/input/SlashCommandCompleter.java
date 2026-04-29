package org.jclaude.cli.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.jclaude.commands.SlashCommandSpec;
import org.jclaude.commands.SlashCommandSpecs;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine {@link Completer} that completes slash commands and their aliases.
 *
 * <p>Mirrors the Rust {@code SlashCommandHelper} from {@code rusty-claude-cli/src/input.rs}: only
 * fires when the line starts with {@code /} and the cursor is at end-of-line; emits one candidate
 * per registered spec name and alias, normalized so duplicates are dropped and stable order is
 * preserved.
 *
 * <p>Additional candidates (tool names, file paths, custom user completions) can be merged via
 * {@link #with_extra(List)}; they are appended after the slash-command set.
 */
public final class SlashCommandCompleter implements Completer {

    private final List<String> base_completions;
    private final List<String> extras;

    public SlashCommandCompleter() {
        this(default_slash_completions(), Collections.emptyList());
    }

    public SlashCommandCompleter(List<String> base_completions, List<String> extras) {
        this.base_completions = normalize(base_completions);
        this.extras = normalize(extras);
    }

    public SlashCommandCompleter with_extra(List<String> extra) {
        return new SlashCommandCompleter(base_completions, extra);
    }

    /**
     * Build the default list of {@code /name} completions from {@link SlashCommandSpecs}. Includes
     * each spec's primary name and every alias, prefixed with a slash.
     */
    public static List<String> default_slash_completions() {
        List<String> out = new ArrayList<>();
        for (SlashCommandSpec spec : SlashCommandSpecs.slash_command_specs()) {
            out.add("/" + spec.name());
            for (String alias : spec.aliases()) {
                out.add("/" + alias);
            }
        }
        return out;
    }

    /** Visible for tests. */
    List<String> base_completions() {
        return base_completions;
    }

    /** Visible for tests. */
    List<String> extras() {
        return extras;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.line();
        int cursor = line.cursor();
        if (cursor != word.length()) {
            return;
        }
        if (!word.startsWith("/")) {
            return;
        }
        for (String candidate : base_completions) {
            if (candidate.startsWith(word)) {
                candidates.add(new Candidate(candidate, candidate, null, null, null, null, true));
            }
        }
        for (String candidate : extras) {
            if (candidate.startsWith(word)) {
                candidates.add(new Candidate(candidate, candidate, null, null, null, null, true));
            }
        }
    }

    /** Mirror of Rust {@code normalize_completions}: keep only "/"-prefixed, deduplicate. */
    static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null) {
                continue;
            }
            if (candidate.startsWith("/")) {
                seen.add(candidate);
            }
        }
        return List.copyOf(seen);
    }

    /** Compute the prefix the user has typed up to {@code cursor}, or {@code null} when not slash. */
    public static String slash_command_prefix(String line, int cursor) {
        if (line == null || cursor != line.length()) {
            return null;
        }
        if (!line.startsWith("/")) {
            return null;
        }
        return line.substring(0, cursor);
    }
}
