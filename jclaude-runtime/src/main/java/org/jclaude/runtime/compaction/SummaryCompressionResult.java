package org.jclaude.runtime.compaction;

/** Outcome of {@link SummaryCompression#compress_summary}. */
public record SummaryCompressionResult(
        String summary,
        int original_chars,
        int compressed_chars,
        int original_lines,
        int compressed_lines,
        int removed_duplicate_lines,
        int omitted_lines,
        boolean truncated) {}
