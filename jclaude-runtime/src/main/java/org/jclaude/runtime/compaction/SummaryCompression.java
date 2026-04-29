package org.jclaude.runtime.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Compresses an already-formatted compaction summary down to a fixed budget. Mirrors the Rust
 * {@code summary_compression} module verbatim.
 */
public final class SummaryCompression {

    private SummaryCompression() {}

    public static SummaryCompressionResult compress_summary(String summary, SummaryCompressionBudget budget) {
        int original_chars = char_count(summary);
        int original_lines = (int) summary.lines().count();

        NormalizedSummary normalized = normalize_lines(summary, budget.max_line_chars());
        if (normalized.lines.isEmpty() || budget.max_chars() == 0 || budget.max_lines() == 0) {
            return new SummaryCompressionResult(
                    "",
                    original_chars,
                    0,
                    original_lines,
                    0,
                    normalized.removed_duplicate_lines,
                    normalized.lines.size(),
                    original_chars > 0);
        }

        List<Integer> selected = select_line_indexes(normalized.lines, budget);
        List<String> compressed_lines = new ArrayList<>();
        for (Integer index : selected) {
            compressed_lines.add(normalized.lines.get(index));
        }
        if (compressed_lines.isEmpty()) {
            compressed_lines.add(truncate_line(normalized.lines.get(0), budget.max_chars()));
        }
        int omitted_lines = Math.max(0, normalized.lines.size() - compressed_lines.size());

        if (omitted_lines > 0) {
            String omission_notice = omission_notice(omitted_lines);
            push_line_with_budget(compressed_lines, omission_notice, budget);
        }

        String compressed_summary = String.join("\n", compressed_lines);

        return new SummaryCompressionResult(
                compressed_summary,
                original_chars,
                char_count(compressed_summary),
                original_lines,
                compressed_lines.size(),
                normalized.removed_duplicate_lines,
                omitted_lines,
                !compressed_summary.equals(summary.trim()));
    }

    public static String compress_summary_text(String summary) {
        return compress_summary(summary, SummaryCompressionBudget.defaults()).summary();
    }

    private static final class NormalizedSummary {
        final List<String> lines = new ArrayList<>();
        int removed_duplicate_lines;
    }

    private static NormalizedSummary normalize_lines(String summary, int max_line_chars) {
        NormalizedSummary result = new NormalizedSummary();
        TreeSet<String> seen = new TreeSet<>();

        summary.lines().forEach(raw_line -> {
            String normalized = collapse_inline_whitespace(raw_line);
            if (normalized.isEmpty()) {
                return;
            }
            String truncated = truncate_line(normalized, max_line_chars);
            String dedupe_key = dedupe_key(truncated);
            if (!seen.add(dedupe_key)) {
                result.removed_duplicate_lines += 1;
                return;
            }
            result.lines.add(truncated);
        });
        return result;
    }

    private static List<Integer> select_line_indexes(List<String> lines, SummaryCompressionBudget budget) {
        TreeSet<Integer> selected = new TreeSet<>();

        for (int priority = 0; priority <= 3; priority++) {
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (selected.contains(index) || line_priority(line) != priority) {
                    continue;
                }

                List<String> candidate = new ArrayList<>();
                for (Integer selected_index : selected) {
                    candidate.add(lines.get(selected_index));
                }
                candidate.add(line);

                if (candidate.size() > budget.max_lines()) {
                    continue;
                }
                if (joined_char_count(candidate) > budget.max_chars()) {
                    continue;
                }
                selected.add(index);
            }
        }
        return new ArrayList<>(selected);
    }

    private static void push_line_with_budget(List<String> lines, String line, SummaryCompressionBudget budget) {
        List<String> candidate = new ArrayList<>(lines);
        candidate.add(line);

        if (candidate.size() <= budget.max_lines() && joined_char_count(candidate) <= budget.max_chars()) {
            lines.add(line);
        }
    }

    private static int joined_char_count(List<String> lines) {
        int total = 0;
        for (String line : lines) {
            total += char_count(line);
        }
        return total + Math.max(0, lines.size() - 1);
    }

    private static int line_priority(String line) {
        if (line.equals("Summary:") || line.equals("Conversation summary:") || is_core_detail(line)) {
            return 0;
        }
        if (is_section_header(line)) {
            return 1;
        }
        if (line.startsWith("- ") || line.startsWith("  - ")) {
            return 2;
        }
        return 3;
    }

    private static boolean is_core_detail(String line) {
        String[] prefixes = {
            "- Scope:",
            "- Current work:",
            "- Pending work:",
            "- Key files referenced:",
            "- Tools mentioned:",
            "- Recent user requests:",
            "- Previously compacted context:",
            "- Newly compacted context:"
        };
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean is_section_header(String line) {
        return line.endsWith(":");
    }

    private static String omission_notice(int omitted_lines) {
        return "- … " + omitted_lines + " additional line(s) omitted.";
    }

    private static String collapse_inline_whitespace(String line) {
        // split_whitespace in Rust treats any unicode whitespace as a separator; using \\s+ matches.
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 1 && parts[0].isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!first) {
                builder.append(' ');
            }
            builder.append(part);
            first = false;
        }
        return builder.toString();
    }

    private static String truncate_line(String line, int max_chars) {
        if (max_chars == 0 || char_count(line) <= max_chars) {
            return line;
        }
        if (max_chars == 1) {
            return "…";
        }
        StringBuilder builder = new StringBuilder();
        int taken = 0;
        int target = Math.max(0, max_chars - 1);
        int i = 0;
        while (i < line.length() && taken < target) {
            int code_point = line.codePointAt(i);
            builder.appendCodePoint(code_point);
            taken += 1;
            i += Character.charCount(code_point);
        }
        builder.append('…');
        return builder.toString();
    }

    private static String dedupe_key(String line) {
        return line.toLowerCase(Locale.ROOT);
    }

    private static int char_count(String value) {
        if (value == null) {
            return 0;
        }
        return value.codePointCount(0, value.length());
    }
}
