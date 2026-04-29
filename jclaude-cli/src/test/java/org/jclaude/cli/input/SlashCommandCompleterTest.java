package org.jclaude.cli.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SlashCommandCompleter}. Mirrors the Rust suite for {@code SlashCommandHelper}
 * in {@code crates/rusty-claude-cli/src/input.rs}.
 */
final class SlashCommandCompleterTest {

    @Test
    void slash_command_prefix_returns_full_typed_text_when_starting_with_slash() {
        assertThat(SlashCommandCompleter.slash_command_prefix("/he", 3)).isEqualTo("/he");
        assertThat(SlashCommandCompleter.slash_command_prefix("/help me", 8)).isEqualTo("/help me");
    }

    @Test
    void slash_command_prefix_returns_null_for_non_slash_input() {
        assertThat(SlashCommandCompleter.slash_command_prefix("hello", 5)).isNull();
    }

    @Test
    void slash_command_prefix_returns_null_when_cursor_not_at_end() {
        assertThat(SlashCommandCompleter.slash_command_prefix("/help", 2)).isNull();
    }

    @Test
    void normalize_keeps_only_slash_prefixed_unique_entries_in_order() {
        List<String> normalized =
                SlashCommandCompleter.normalize(List.of("/help", "/help", "no-slash", "/clear", "/clear"));

        assertThat(normalized).containsExactly("/help", "/clear");
    }

    @Test
    void default_slash_completions_includes_canonical_commands() {
        List<String> completions = SlashCommandCompleter.default_slash_completions();

        assertThat(completions).contains("/help", "/clear", "/compact", "/cost");
    }

    @Test
    void completer_emits_only_matches_that_start_with_typed_prefix() {
        SlashCommandCompleter completer = new SlashCommandCompleter(List.of("/help", "/hello", "/status"), List.of());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed_line("/he", 3), candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertThat(values).containsExactly("/help", "/hello");
    }

    @Test
    void completer_skips_non_slash_input() {
        SlashCommandCompleter completer = new SlashCommandCompleter(List.of("/help"), List.of());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed_line("hello", 5), candidates);

        assertThat(candidates).isEmpty();
    }

    @Test
    void completer_skips_when_cursor_not_at_end_of_line() {
        SlashCommandCompleter completer = new SlashCommandCompleter(List.of("/help"), List.of());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed_line("/help", 2), candidates);

        assertThat(candidates).isEmpty();
    }

    @Test
    void with_extra_appends_user_supplied_completions_after_built_in_set() {
        SlashCommandCompleter completer = new SlashCommandCompleter(List.of("/help"), List.of("/foo"));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed_line("/", 1), candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertThat(values).containsExactly("/help", "/foo");
    }

    private static ParsedLine parsed_line(String word, int cursor) {
        return new ParsedLine() {
            @Override
            public String word() {
                return word;
            }

            @Override
            public int wordCursor() {
                return cursor;
            }

            @Override
            public int wordIndex() {
                return 0;
            }

            @Override
            public List<String> words() {
                return List.of(word);
            }

            @Override
            public String line() {
                return word;
            }

            @Override
            public int cursor() {
                return cursor;
            }

            @SuppressWarnings("unused")
            public LineReader reader() {
                return null;
            }
        };
    }
}
