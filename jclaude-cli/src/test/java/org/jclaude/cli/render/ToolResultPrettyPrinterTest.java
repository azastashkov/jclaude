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
    void write_file_create_renders_human_summary() {
        String json = "{\"kind\":\"create\",\"file_path\":\"out.txt\",\"bytes_written\":42}";
        String out = ToolResultPrettyPrinter.format("write_file", json);
        assertThat(out).isEqualTo("Created out.txt (42 bytes)");
    }

    @Test
    void write_file_without_size_falls_back_to_path() {
        String json = "{\"kind\":\"create\",\"file_path\":\"out.txt\"}";
        String out = ToolResultPrettyPrinter.format("write_file", json);
        assertThat(out).isEqualTo("Created out.txt");
    }

    @Test
    void edit_file_renders_replacement_count() {
        String json = "{\"file_path\":\"a.txt\",\"replacements\":3}";
        String out = ToolResultPrettyPrinter.format("edit_file", json);
        assertThat(out).isEqualTo("Edited a.txt (3 replacements)");
    }

    @Test
    void grep_search_count_mode_renders_singular_or_plural() {
        assertThat(ToolResultPrettyPrinter.format("grep_search", "{\"count\":1}"))
                .isEqualTo("1 occurrence");
        assertThat(ToolResultPrettyPrinter.format("grep_search", "{\"count\":4}"))
                .isEqualTo("4 occurrences");
    }

    @Test
    void glob_search_lists_matches_one_per_line() {
        String json = "{\"matches\":[\"a.java\",\"b.java\"]}";
        String out = ToolResultPrettyPrinter.format("glob_search", json);
        assertThat(out).startsWith("2 matches:").contains("a.java").contains("b.java");
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
