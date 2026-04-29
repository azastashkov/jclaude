package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobSearchTest {

    @Test
    void finds_files_matching_pattern(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("demo.rs"), "fn main() {}");
        Files.writeString(workspace.resolve("other.txt"), "skip");

        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.rs", null, workspace));

        assertThat(output.num_files()).isEqualTo(1);
        assertThat(output.filenames()).hasSize(1);
        assertThat(output.filenames().get(0)).endsWith("demo.rs");
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void honors_explicit_search_root(@TempDir Path workspace) throws IOException {
        Path inner = workspace.resolve("inner");
        Files.createDirectory(inner);
        Files.writeString(inner.resolve("a.rs"), "");
        Files.writeString(workspace.resolve("b.rs"), "");

        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.rs", inner.toString(), workspace));

        assertThat(output.filenames()).hasSize(1);
        assertThat(output.filenames().get(0)).endsWith("a.rs");
    }

    @Test
    void glob_search_with_braces_finds_files(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.rs"), "fn main() {}");
        Files.writeString(workspace.resolve("b.toml"), "[package]");
        Files.writeString(workspace.resolve("c.txt"), "hello");

        GlobSearch.Output output =
                GlobSearch.execute(GlobSearch.Input.of("*.{rs,toml}", workspace.toString(), workspace));

        assertThat(output.num_files()).isEqualTo(2);
    }

    @Test
    void skips_excluded_directories(@TempDir Path workspace) throws IOException {
        Path target = workspace.resolve("target");
        Files.createDirectory(target);
        Files.writeString(target.resolve("ignored.java"), "");
        Files.writeString(workspace.resolve("kept.java"), "");

        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.java", null, workspace));

        assertThat(output.filenames()).hasSize(1);
        assertThat(output.filenames().get(0)).endsWith("kept.java");
    }

    @Test
    void truncates_at_one_hundred_results(@TempDir Path workspace) throws IOException {
        for (int i = 0; i < 105; i++) {
            Files.writeString(workspace.resolve("file" + i + ".log"), "");
        }
        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.log", null, workspace));

        assertThat(output.filenames()).hasSize(100);
        assertThat(output.truncated()).isTrue();
    }

    @Test
    void returns_empty_when_no_matches(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("only.txt"), "");
        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.rs", null, workspace));

        assertThat(output.num_files()).isEqualTo(0);
        assertThat(output.filenames()).isEmpty();
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void records_duration_milliseconds(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.rs"), "");
        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("**/*.rs", null, workspace));
        assertThat(output.duration_ms()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void deduplicates_overlapping_brace_patterns(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("dup.rs"), "");
        GlobSearch.Output output = GlobSearch.execute(GlobSearch.Input.of("*.{rs,rs}", null, workspace));
        assertThat(output.num_files()).isEqualTo(1);
    }
}
