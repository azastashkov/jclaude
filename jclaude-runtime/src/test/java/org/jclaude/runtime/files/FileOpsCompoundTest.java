package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compound mechanical ports of higher-level workflows from
 * {@code crates/runtime/src/file_ops.rs} that drive multiple file ops in one
 * scenario. The narrower per-op tests already live alongside in
 * {@link ReadFileTest}, {@link WriteFileTest}, {@link EditFileTest}, etc.
 */
final class FileOpsCompoundTest {

    @Test
    void reads_and_writes_files(@TempDir Path workspace) throws IOException {
        WriteFile.Output write_output =
                WriteFile.execute(new WriteFile.Input("read-write.txt", "one\ntwo\nthree", workspace));
        assertThat(write_output.kind()).isEqualTo("create");

        ReadFile.Output read_output = ReadFile.execute(ReadFile.Input.of("read-write.txt", 1, 1, workspace));
        assertThat(read_output.file().content().trim()).contains("two");
    }

    @Test
    void edits_file_contents(@TempDir Path workspace) throws IOException {
        WriteFile.execute(new WriteFile.Input("edit.txt", "alpha beta alpha", workspace));

        EditFile.Output output = EditFile.execute(new EditFile.Input("edit.txt", "alpha", "omega", true, workspace));

        assertThat(output.replace_all()).isTrue();
        assertThat(Files.readString(workspace.resolve("edit.txt"))).isEqualTo("omega beta omega");
    }

    @Test
    void enforces_workspace_boundary(@TempDir Path workspace, @TempDir Path other) throws IOException {
        // Writing inside the workspace succeeds.
        WriteFile.execute(new WriteFile.Input("inside.txt", "safe content", workspace));
        ReadFile.Output read = ReadFile.execute(ReadFile.Input.of("inside.txt", workspace));
        assertThat(read.file().content()).contains("safe content");

        // Reading a path that escapes the workspace (via the other temp dir's
        // absolute path) fails with a workspace-boundary error.
        Path outside = other.resolve("escape.txt");
        Files.writeString(outside, "unsafe");
        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of(outside.toString(), workspace)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void globs_and_greps_directory(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("demo.rs"), "fn main() {\n println!(\"hello\");\n}\n");

        GlobSearch.Output glob_out = GlobSearch.execute(GlobSearch.Input.of("**/*.rs", null, workspace));
        assertThat(glob_out.num_files()).isEqualTo(1);

        GrepSearch.Output grep_out =
                GrepSearch.execute(GrepSearch.Input.of("hello", ".", GrepSearch.MODE_CONTENT, workspace));
        assertThat(grep_out.toString()).contains("hello");
    }
}
