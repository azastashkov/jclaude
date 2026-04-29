package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutputFormat#parse(String)}. Mirrors the {@code CliOutputFormat::parse}
 * checks at crates/rusty-claude-cli/src/main.rs around the {@code parses_bare_prompt_and_json_output_flag}
 * test (line 9775) and the {@code OutputFormat} variants used throughout {@code parse_args}.
 */
final class OutputFormatTest {

    @Test
    void parse_returns_text_when_input_is_null_or_default() {
        assertThat(OutputFormat.parse(null)).isEqualTo(OutputFormat.TEXT);
        assertThat(OutputFormat.parse("text")).isEqualTo(OutputFormat.TEXT);
    }

    @Test
    void parse_returns_json_for_lowercase_and_uppercase_values() {
        assertThat(OutputFormat.parse("json")).isEqualTo(OutputFormat.JSON);
        assertThat(OutputFormat.parse("JSON")).isEqualTo(OutputFormat.JSON);
        assertThat(OutputFormat.parse("Json")).isEqualTo(OutputFormat.JSON);
    }

    @Test
    void parse_trims_whitespace_around_value() {
        assertThat(OutputFormat.parse("  text  ")).isEqualTo(OutputFormat.TEXT);
        assertThat(OutputFormat.parse(" \tjson\n")).isEqualTo(OutputFormat.JSON);
    }

    @Test
    void parse_rejects_unknown_format_with_helpful_message() {
        assertThatThrownBy(() -> OutputFormat.parse("xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown output format")
                .hasMessageContaining("xml");
    }
}
