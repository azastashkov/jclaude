package org.jclaude.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.cli.render.AnsiPalette;
import org.junit.jupiter.api.Test;

/**
 * Verifies the claude-code-style status line composer reflects the latest signal received from
 * the model adapter: tool name when known, char count when streaming, plain "thinking" otherwise.
 */
class ReplStatusLineTest {

    private static final AnsiPalette NO_COLOR = AnsiPalette.with_color_disabled();

    @Test
    void formats_idle_thinking_state_when_no_signal_received_yet() {
        String line = Repl.format_claude_code_status(NO_COLOR, "·", "Infusing", 4L, 0L, null, "thinking");
        assertThat(line).contains("Infusing").contains("4s").contains("thinking");
        // No char count and no tool — the parenthetical should not advertise either.
        assertThat(line).doesNotContain("chars").doesNotContain("→");
    }

    @Test
    void shows_streaming_verb_and_char_count_once_text_starts_arriving() {
        String line = Repl.format_claude_code_status(NO_COLOR, "·", "Infusing", 6L, 1400L, null, "thinking");
        assertThat(line).contains("Streaming").contains("6s").contains("↓ 1.4k chars");
        // Must not still call itself "Infusing" — the verb should reflect actual activity.
        assertThat(line).doesNotContain("Infusing");
    }

    @Test
    void shows_calling_tool_verb_when_a_tool_name_is_known() {
        String line = Repl.format_claude_code_status(NO_COLOR, "·", "Infusing", 12L, 800L, "bash", "thinking");
        assertThat(line).contains("Calling bash").contains("12s").contains("↓ 800 chars");
        assertThat(line).doesNotContain("Infusing").doesNotContain("Streaming");
    }

    @Test
    void preserves_thought_for_label_once_observed() {
        String line = Repl.format_claude_code_status(NO_COLOR, "·", "Infusing", 30L, 0L, null, "thought for 5s");
        assertThat(line).contains("thought for 5s");
    }
}
