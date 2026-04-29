package org.jclaude.runtime.compaction;

/** Budget controlling how aggressively a summary is compressed. */
public record SummaryCompressionBudget(int max_chars, int max_lines, int max_line_chars) {

    public static final int DEFAULT_MAX_CHARS = 1_200;
    public static final int DEFAULT_MAX_LINES = 24;
    public static final int DEFAULT_MAX_LINE_CHARS = 160;

    public static SummaryCompressionBudget defaults() {
        return new SummaryCompressionBudget(DEFAULT_MAX_CHARS, DEFAULT_MAX_LINES, DEFAULT_MAX_LINE_CHARS);
    }
}
