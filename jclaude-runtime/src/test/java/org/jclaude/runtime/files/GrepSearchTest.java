package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepSearchTest {

    @Test
    void files_with_matches_default_mode(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello world\nbye");
        Files.writeString(workspace.resolve("b.txt"), "no match here");

        GrepSearch.Output output = GrepSearch.execute(GrepSearch.Input.of("hello", workspace.toString(), workspace));

        assertThat(output.mode()).isEqualTo(GrepSearch.MODE_FILES_WITH_MATCHES);
        assertThat(output.num_files()).isEqualTo(1);
        assertThat(output.filenames().get(0)).endsWith("a.txt");
    }

    @Test
    void content_mode_emits_matched_lines_with_line_numbers(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.rs"), "fn main() {\n    println!(\"hello\");\n}\n");

        GrepSearch.Output output = GrepSearch.execute(
                GrepSearch.Input.of("hello", workspace.toString(), GrepSearch.MODE_CONTENT, workspace));

        assertThat(output.mode()).isEqualTo(GrepSearch.MODE_CONTENT);
        assertThat(output.content()).isPresent();
        assertThat(output.content().get()).contains("hello").contains(":2:");
        assertThat(output.num_lines()).contains(1);
    }

    @Test
    void count_mode_returns_total_match_count(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "abc\nabcabc\nxxx");

        GrepSearch.Output output =
                GrepSearch.execute(GrepSearch.Input.of("abc", workspace.toString(), GrepSearch.MODE_COUNT, workspace));

        assertThat(output.mode()).isEqualTo(GrepSearch.MODE_COUNT);
        assertThat(output.num_matches()).contains(3);
        assertThat(output.num_files()).isEqualTo(1);
    }

    @Test
    void skips_binary_files(@TempDir Path workspace) throws IOException {
        Files.write(workspace.resolve("bin.bin"), new byte[] {0x00, 'h', 'e', 'l', 'l', 'o'});
        Files.writeString(workspace.resolve("a.txt"), "hello");

        GrepSearch.Output output = GrepSearch.execute(GrepSearch.Input.of("hello", workspace.toString(), workspace));

        assertThat(output.num_files()).isEqualTo(1);
        assertThat(output.filenames().get(0)).endsWith("a.txt");
    }

    @Test
    void skips_default_excluded_directories(@TempDir Path workspace) throws IOException {
        Path noise = workspace.resolve("node_modules");
        Files.createDirectory(noise);
        Files.writeString(noise.resolve("noisy.txt"), "needle");
        Files.writeString(workspace.resolve("kept.txt"), "needle");

        GrepSearch.Output output = GrepSearch.execute(GrepSearch.Input.of("needle", workspace.toString(), workspace));

        assertThat(output.filenames()).hasSize(1);
        assertThat(output.filenames().get(0)).endsWith("kept.txt");
    }

    @Test
    void supports_regex_metacharacters(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "id: 42\nid: 7");

        GrepSearch.Output output = GrepSearch.execute(
                GrepSearch.Input.of("id:\\s*\\d+", workspace.toString(), GrepSearch.MODE_CONTENT, workspace));

        assertThat(output.num_lines()).contains(2);
    }

    @Test
    void returns_no_matches_for_empty_directory(@TempDir Path workspace) throws IOException {
        GrepSearch.Output output = GrepSearch.execute(GrepSearch.Input.of("foo", workspace.toString(), workspace));
        assertThat(output.num_files()).isEqualTo(0);
        assertThat(output.filenames()).isEmpty();
    }

    @Test
    void searches_a_single_file_directly(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "alpha\nbeta");

        GrepSearch.Output output = GrepSearch.execute(GrepSearch.Input.of("beta", file.toString(), workspace));

        assertThat(output.num_files()).isEqualTo(1);
        assertThat(output.filenames().get(0)).endsWith("a.txt");
    }

    @Test
    void rejects_invalid_regex_syntax(@TempDir Path workspace) {
        assertThatThrownBy(
                        () -> GrepSearch.execute(GrepSearch.Input.of("(unbalanced", workspace.toString(), workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.INVALID_INPUT);
    }

    @Test
    void rejects_path_traversal(@TempDir Path workspace, @TempDir Path other) {
        assertThatThrownBy(() -> GrepSearch.execute(GrepSearch.Input.of("foo", other.toString(), workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED);
    }

    @Test
    void content_mode_preserves_match_order(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "first match\nfiller\nsecond match\nanother match");

        GrepSearch.Output output = GrepSearch.execute(
                GrepSearch.Input.of("match", workspace.toString(), GrepSearch.MODE_CONTENT, workspace));

        assertThat(output.num_lines()).contains(3);
        assertThat(output.content().orElse("")).containsSubsequence("first match", "second match", "another match");
    }
}
