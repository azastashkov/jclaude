package org.jclaude.cli.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclaude.api.json.JclaudeMappers;

/**
 * Converts a tool dispatcher's JSON-shaped result into a human-readable string for the REPL. The
 * dispatcher serializes typed records (e.g. {@link org.jclaude.runtime.files.ReadFile.Output})
 * to JSON so the model can consume them, but raw JSON dumps with escaped {@code \n} make for
 * unreadable terminal output. This printer recognizes the canonical shapes per tool name and
 * extracts the visible content (file body, command stdout, match list, etc).
 *
 * <p>Falls back to pretty-printing the JSON when the shape isn't recognized.
 */
public final class ToolResultPrettyPrinter {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private ToolResultPrettyPrinter() {}

    public static String format(String tool_name, String raw_output) {
        if (raw_output == null || raw_output.isBlank()) {
            return "";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw_output);
        } catch (Exception e) {
            return raw_output; // not JSON — render verbatim
        }
        if (node == null || node.isMissingNode()) {
            return raw_output;
        }
        if (tool_name == null) {
            return pretty_fallback(node);
        }
        return switch (tool_name) {
            case "read_file" -> format_read_file(node, raw_output);
            case "write_file" -> format_write_file(node, raw_output);
            case "edit_file" -> format_edit_file(node, raw_output);
            case "glob_search" -> format_glob_search(node, raw_output);
            case "grep_search" -> format_grep_search(node, raw_output);
            case "bash" -> format_bash(node, raw_output);
            case "TodoWrite" -> format_todo_write(node, raw_output);
            case "Sleep" -> format_sleep(node, raw_output);
            default -> pretty_fallback(node);
        };
    }

    private static String format_read_file(JsonNode node, String fallback) {
        JsonNode file = node.path("file");
        String path = file.path("file_path").asText("");
        String content = file.path("content").asText(null);
        if (content == null) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        if (!path.isEmpty()) {
            sb.append(path).append(":\n");
        }
        sb.append(content);
        return sb.toString();
    }

    // WriteFile.Output = (kind, file_path, content, structured_patch, original_file).
    private static String format_write_file(JsonNode node, String fallback) {
        String path = node.path("file_path").asText(null);
        if (path == null) {
            return fallback;
        }
        String kind = node.path("kind").asText("");
        StringBuilder sb = new StringBuilder();
        if ("create".equalsIgnoreCase(kind)) {
            sb.append("Created ").append(path);
        } else if ("update".equalsIgnoreCase(kind) || "overwrite".equalsIgnoreCase(kind)) {
            sb.append("Updated ").append(path);
        } else {
            sb.append("Wrote ").append(path);
        }
        // Derive size from the content field when present (the dispatcher always serializes it).
        String content = node.path("content").asText(null);
        if (content != null) {
            sb.append(" (")
                    .append(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .append(" bytes)");
        }
        return sb.toString();
    }

    // EditFile.Output = (file_path, old_string, new_string, original_file, structured_patch,
    //                    user_modified, replace_all). Number of replacements = patch hunk count.
    private static String format_edit_file(JsonNode node, String fallback) {
        String path = node.path("file_path").asText(null);
        if (path == null) {
            return fallback;
        }
        JsonNode patch = node.path("structured_patch");
        long hunks = patch.isArray() ? patch.size() : -1;
        StringBuilder sb = new StringBuilder();
        sb.append("Edited ").append(path);
        if (hunks >= 0) {
            sb.append(" (")
                    .append(hunks)
                    .append(" hunk")
                    .append(hunks == 1 ? "" : "s")
                    .append(")");
        }
        return sb.toString();
    }

    // GlobSearch.Output = (duration_ms, num_files, filenames, truncated).
    private static String format_glob_search(JsonNode node, String fallback) {
        JsonNode filenames = node.path("filenames");
        if (!filenames.isArray()) {
            return fallback;
        }
        int n = filenames.size();
        StringBuilder sb = new StringBuilder();
        sb.append(n).append(n == 1 ? " file" : " files");
        if (node.path("truncated").asBoolean(false)) {
            sb.append(" (truncated)");
        }
        if (n == 0) {
            return sb.toString();
        }
        sb.append(":");
        for (JsonNode m : filenames) {
            sb.append('\n').append(m.asText());
        }
        return sb.toString();
    }

    // GrepSearch.Output = (mode, num_files, filenames, content, num_lines, num_matches).
    // Three output modes: "files_with_matches" → list filenames; "content" → show content/lines;
    // "count" → "N occurrences across M files".
    private static String format_grep_search(JsonNode node, String fallback) {
        String mode = node.path("mode").asText("");
        int num_files = node.path("num_files").asInt(0);
        JsonNode filenames = node.path("filenames");
        JsonNode content = node.path("content");
        JsonNode num_matches = node.path("num_matches");
        JsonNode num_lines = node.path("num_lines");

        if ("count".equals(mode) && num_matches.isInt()) {
            int total = num_matches.asInt();
            return total + (total == 1 ? " occurrence" : " occurrences") + " across " + num_files
                    + (num_files == 1 ? " file" : " files");
        }
        if ("content".equals(mode) && content.isTextual()) {
            return content.asText();
        }
        if (("content".equals(mode) || "files_with_matches".equals(mode)) && num_lines.isInt() && content.isTextual()) {
            return content.asText() + "\n[" + num_lines.asInt() + " lines]";
        }
        if (filenames.isArray()) {
            int n = filenames.size();
            if (n == 0) {
                return "no matches";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(n).append(n == 1 ? " file" : " files").append(" with matches:");
            for (JsonNode m : filenames) {
                sb.append('\n').append(m.asText());
            }
            return sb.toString();
        }
        return fallback;
    }

    private static String format_bash(JsonNode node, String fallback) {
        String stdout = node.path("stdout").asText(null);
        String stderr = node.path("stderr").asText(null);
        long exit_code = node.path("exit_code").asLong(0);
        boolean timed_out = node.path("timed_out").asBoolean(false);
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) {
            sb.append(stdout);
        }
        if (stderr != null && !stderr.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr] ").append(stderr);
        }
        if (exit_code != 0 || timed_out) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[exit ")
                    .append(exit_code)
                    .append(timed_out ? ", timed out" : "")
                    .append("]");
        }
        if (sb.length() == 0) {
            return fallback;
        }
        return sb.toString();
    }

    private static String format_todo_write(JsonNode node, String fallback) {
        JsonNode todos = node.path("todos");
        if (!todos.isArray() || todos.size() == 0) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < todos.size(); i++) {
            JsonNode t = todos.get(i);
            if (i > 0) sb.append('\n');
            String status = t.path("status").asText("pending");
            String mark =
                    switch (status) {
                        case "completed" -> "[x]";
                        case "in_progress" -> "[~]";
                        default -> "[ ]";
                    };
            sb.append(mark).append(' ').append(t.path("content").asText(""));
        }
        return sb.toString();
    }

    private static String format_sleep(JsonNode node, String fallback) {
        long ms = node.path("slept_ms").asLong(-1);
        if (ms < 0) return fallback;
        return "Slept " + ms + " ms";
    }

    private static String pretty_fallback(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
