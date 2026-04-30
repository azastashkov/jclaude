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

    /**
     * One-line summary suitable for Claude Code-style display ({@code ⎿  Read 47 lines (1.2 kB)}).
     * Mirrors the Claude Code CLI convention of showing a concise audit line per tool call rather
     * than echoing the model-visible JSON envelope.
     *
     * <p>Falls back to {@link #format(String, String)} for tools whose output is already terse
     * (bash stdout, search counts) or for unknown tool names.
     */
    public static String format_terse(String tool_name, String raw_output) {
        if (raw_output == null || raw_output.isBlank()) {
            return "";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw_output);
        } catch (Exception e) {
            return clip_one_line(raw_output);
        }
        if (node == null || node.isMissingNode() || tool_name == null) {
            return clip_one_line(raw_output);
        }
        return switch (tool_name) {
            case "read_file" -> terse_read_file(node);
            case "write_file" -> format_write_file(node, raw_output);
            case "edit_file" -> format_edit_file(node, raw_output);
            case "glob_search" -> terse_glob_search(node);
            case "grep_search" -> terse_grep_search(node);
            case "bash" -> terse_bash(node);
            case "TodoWrite" -> terse_todo_write(node);
            case "Sleep" -> format_sleep(node, raw_output);
            case "WebFetch" -> terse_web_fetch(node);
            case "WebSearch" -> terse_web_search(node);
            default -> clip_one_line(format(tool_name, raw_output));
        };
    }

    private static String terse_read_file(JsonNode node) {
        JsonNode file = node.path("file");
        int total = file.path("total_lines").asInt(file.path("num_lines").asInt(0));
        String content = file.path("content").asText("");
        int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return "Read " + total + (total == 1 ? " line" : " lines") + " (" + human_bytes(bytes) + ")";
    }

    private static String terse_glob_search(JsonNode node) {
        int n = node.path("num_files").asInt(node.path("filenames").size());
        return n + (n == 1 ? " file" : " files") + " found"
                + (node.path("truncated").asBoolean(false) ? " (truncated)" : "");
    }

    private static String terse_grep_search(JsonNode node) {
        String mode = node.path("mode").asText("");
        int num_files = node.path("num_files").asInt(0);
        if ("count".equals(mode) && node.has("num_matches")) {
            int n = node.path("num_matches").asInt(0);
            return n + (n == 1 ? " match" : " matches") + " across " + num_files
                    + (num_files == 1 ? " file" : " files");
        }
        if ("content".equals(mode) && node.has("num_lines")) {
            int n = node.path("num_lines").asInt(0);
            return n + (n == 1 ? " match line" : " match lines") + " across " + num_files
                    + (num_files == 1 ? " file" : " files");
        }
        if (num_files == 0) return "no matches";
        return num_files + (num_files == 1 ? " file" : " files") + " with matches";
    }

    private static String terse_bash(JsonNode node) {
        long exit_code = node.path("exit_code").asLong(0);
        boolean timed_out = node.path("timed_out").asBoolean(false);
        String stdout = node.path("stdout").asText("");
        String stderr = node.path("stderr").asText("");
        if (timed_out) return "timed out";
        if (exit_code != 0) {
            String first = first_line(stderr.isEmpty() ? stdout : stderr);
            return "exit " + exit_code + (first.isEmpty() ? "" : ": " + clip_one_line(first));
        }
        if (stdout.isEmpty()) return "ok (no output)";
        // Single-line stdout: show it; multi-line: summarize line count.
        int lines = stdout.split("\n", -1).length - (stdout.endsWith("\n") ? 1 : 0);
        if (lines <= 1) return clip_one_line(stdout);
        return "ok (" + lines + " lines, " + human_bytes(stdout.length()) + ")";
    }

    private static String terse_todo_write(JsonNode node) {
        JsonNode todos = node.path("todos");
        if (!todos.isArray()) return "ok";
        int n = todos.size();
        int done = 0;
        for (JsonNode t : todos) {
            if ("completed".equals(t.path("status").asText(""))) done++;
        }
        return done + "/" + n + " todos completed";
    }

    private static String terse_web_fetch(JsonNode node) {
        int status = node.path("status_code").asInt(0);
        int bytes = node.path("body").asText("").length();
        return "HTTP " + status + " (" + human_bytes(bytes) + ")";
    }

    private static String terse_web_search(JsonNode node) {
        JsonNode results = node.path("results");
        int n = results.isArray() ? results.size() : 0;
        return n + (n == 1 ? " result" : " results") + " (status="
                + node.path("status").asText("?") + ")";
    }

    private static String human_bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return Math.round(n / 1024.0 * 10.0) / 10.0 + " kB";
        return Math.round(n / (1024.0 * 1024.0) * 10.0) / 10.0 + " MB";
    }

    private static String first_line(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String clip_one_line(String s) {
        if (s == null) return "";
        String single = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return single.length() <= 100 ? single : single.substring(0, 99) + "…";
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
