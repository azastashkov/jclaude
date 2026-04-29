package org.jclaude.runtime.files;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link StructuredPatchHunk} payloads. Mirrors the Rust {@code make_patch} helper: emit a
 * single hunk whose {@code lines} list contains every original line as {@code -line} followed by
 * every updated line as {@code +line}.
 */
final class Patches {

    private Patches() {}

    static List<StructuredPatchHunk> make_patch(String original, String updated) {
        List<String> originalLines = split_lines(original);
        List<String> updatedLines = split_lines(updated);
        List<String> hunkLines = new ArrayList<>(originalLines.size() + updatedLines.size());
        for (String line : originalLines) {
            hunkLines.add("-" + line);
        }
        for (String line : updatedLines) {
            hunkLines.add("+" + line);
        }
        return List.of(new StructuredPatchHunk(1, originalLines.size(), 1, updatedLines.size(), hunkLines));
    }

    /**
     * Mirrors Rust {@code str::lines}: trailing newlines do not produce empty trailing entries
     * and the resulting list excludes the line terminator.
     */
    static List<String> split_lines(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return result;
        }
        int length = content.length();
        int start = 0;
        for (int i = 0; i < length; i++) {
            char ch = content.charAt(i);
            if (ch == '\n') {
                int end = i;
                if (end > start && content.charAt(end - 1) == '\r') {
                    end--;
                }
                result.add(content.substring(start, end));
                start = i + 1;
            }
        }
        if (start < length) {
            int end = length;
            if (end > start && content.charAt(end - 1) == '\r') {
                end--;
            }
            result.add(content.substring(start, end));
        }
        return result;
    }
}
