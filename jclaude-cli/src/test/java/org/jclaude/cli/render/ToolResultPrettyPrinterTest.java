package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies that {@link ToolResultPrettyPrinter} unpacks the dispatcher's JSON envelopes. */
final class ToolResultPrettyPrinterTest {

    @Test
    void read_file_unpacks_path_and_content_with_real_newlines() {
        String json =
                "{\"kind\":\"text\",\"file\":{\"file_path\":\"/tmp/x.md\"," + "\"content\":\"line1\\nline2\\nline3\"}}";
        String out = ToolResultPrettyPrinter.format("read_file", json);
        assertThat(out).contains("/tmp/x.md:").contains("line1\nline2\nline3");
    }

    @Test
    void write_file_create_renders_human_summary_with_byte_count_from_content() {
        // WriteFile.Output = (kind, file_path, content, structured_patch, original_file).
        String json = "{\"kind\":\"create\",\"file_path\":\"out.txt\",\"content\":\"hello world\"}";
        String out = ToolResultPrettyPrinter.format("write_file", json);
        assertThat(out).isEqualTo("Created out.txt (11 bytes)");
    }

    @Test
    void write_file_without_content_renders_path_only() {
        String json = "{\"kind\":\"create\",\"file_path\":\"out.txt\"}";
        String out = ToolResultPrettyPrinter.format("write_file", json);
        assertThat(out).isEqualTo("Created out.txt");
    }

    @Test
    void edit_file_renders_hunk_count_from_structured_patch() {
        // EditFile.Output carries structured_patch (a list of hunks); count those.
        String json = "{\"file_path\":\"a.txt\",\"structured_patch\":[{},{},{}]}";
        String out = ToolResultPrettyPrinter.format("edit_file", json);
        assertThat(out).isEqualTo("Edited a.txt (3 hunks)");
    }

    @Test
    void grep_search_count_mode_summarises_total_and_file_span() {
        // GrepSearch.Output mode=count → num_matches across num_files.
        String json = "{\"mode\":\"count\",\"num_files\":2,\"num_matches\":7,\"filenames\":[\"a.java\",\"b.java\"]}";
        String out = ToolResultPrettyPrinter.format("grep_search", json);
        assertThat(out).isEqualTo("7 occurrences across 2 files");
    }

    @Test
    void grep_search_files_with_matches_lists_files() {
        String json = "{\"mode\":\"files_with_matches\",\"num_files\":2,\"filenames\":[\"a.java\",\"b.java\"]}";
        String out = ToolResultPrettyPrinter.format("grep_search", json);
        assertThat(out).startsWith("2 files with matches:").contains("a.java").contains("b.java");
    }

    @Test
    void grep_search_no_matches_renders_short_message() {
        String json = "{\"mode\":\"files_with_matches\",\"num_files\":0,\"filenames\":[]}";
        String out = ToolResultPrettyPrinter.format("grep_search", json);
        assertThat(out).isEqualTo("no matches");
    }

    @Test
    void glob_search_lists_filenames_one_per_line() {
        // GlobSearch.Output uses field "filenames", not "matches".
        String json = "{\"duration_ms\":102,\"num_files\":2,\"filenames\":[\"a.java\",\"b.java\"],\"truncated\":false}";
        String out = ToolResultPrettyPrinter.format("glob_search", json);
        assertThat(out).startsWith("2 files:").contains("a.java").contains("b.java");
    }

    @Test
    void glob_search_zero_results_renders_short_message() {
        String json = "{\"duration_ms\":102,\"num_files\":0,\"filenames\":[],\"truncated\":false}";
        String out = ToolResultPrettyPrinter.format("glob_search", json);
        assertThat(out).isEqualTo("0 files");
    }

    @Test
    void glob_search_truncated_flag_is_surfaced() {
        String json = "{\"duration_ms\":102,\"num_files\":1,\"filenames\":[\"a.java\"],\"truncated\":true}";
        String out = ToolResultPrettyPrinter.format("glob_search", json);
        assertThat(out).startsWith("1 file (truncated):").contains("a.java");
    }

    @Test
    void bash_renders_stdout_only_when_exit_zero() {
        String json = "{\"stdout\":\"banana\\n\",\"stderr\":\"\",\"exit_code\":0,\"timed_out\":false}";
        String out = ToolResultPrettyPrinter.format("bash", json);
        assertThat(out).isEqualTo("banana\n");
    }

    @Test
    void bash_appends_exit_marker_on_nonzero() {
        String json = "{\"stdout\":\"\",\"stderr\":\"oops\",\"exit_code\":2,\"timed_out\":false}";
        String out = ToolResultPrettyPrinter.format("bash", json);
        assertThat(out).contains("[stderr] oops").contains("[exit 2]");
    }

    @Test
    void unknown_tool_pretty_prints_json_fallback() {
        String json = "{\"foo\":\"bar\"}";
        String out = ToolResultPrettyPrinter.format("UnknownTool", json);
        assertThat(out).contains("\"foo\"").contains("\"bar\"");
    }

    @Test
    void non_json_input_passes_through_unchanged() {
        String out = ToolResultPrettyPrinter.format("read_file", "this is not json");
        assertThat(out).isEqualTo("this is not json");
    }

    @Test
    void blank_input_yields_empty_string() {
        assertThat(ToolResultPrettyPrinter.format("read_file", "")).isEmpty();
        assertThat(ToolResultPrettyPrinter.format("read_file", null)).isEmpty();
    }
}
