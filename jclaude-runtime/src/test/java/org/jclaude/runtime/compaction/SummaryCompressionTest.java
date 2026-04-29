package org.jclaude.runtime.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SummaryCompressionTest {

    @Test
    void collapses_whitespace_and_duplicate_lines() {
        String summary =
                "Conversation summary:\n\n- Scope:   compact   earlier   messages.\n- Scope: compact earlier messages.\n- Current work: update runtime module.\n";

        SummaryCompressionResult result =
                SummaryCompression.compress_summary(summary, SummaryCompressionBudget.defaults());

        assertThat(result.removed_duplicate_lines()).isEqualTo(1);
        assertThat(result.summary()).contains("- Scope: compact earlier messages.");
        assertThat(result.summary()).doesNotContain("  compact   earlier");
    }

    @Test
    void keeps_core_lines_when_budget_is_tight() {
        String summary = String.join(
                "\n",
                "Conversation summary:",
                "- Scope: 18 earlier messages compacted.",
                "- Current work: finish summary compression.",
                "- Key timeline:",
                "  - user: asked for a working implementation.",
                "  - assistant: inspected runtime compaction flow.",
                "  - tool: cargo check succeeded.");

        SummaryCompressionResult result =
                SummaryCompression.compress_summary(summary, new SummaryCompressionBudget(120, 3, 80));

        assertThat(result.summary()).contains("Conversation summary:");
        assertThat(result.summary()).contains("- Scope: 18 earlier messages compacted.");
        assertThat(result.summary()).contains("- Current work: finish summary compression.");
        assertThat(result.omitted_lines()).isGreaterThan(0);
    }

    @Test
    void provides_a_default_text_only_helper() {
        String summary = "Summary:\n\nA short line.";

        String compressed = SummaryCompression.compress_summary_text(summary);

        assertThat(compressed).isEqualTo("Summary:\nA short line.");
    }
}
