package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileTest {

    @Test
    void creates_file_when_missing(@TempDir Path workspace) throws IOException {
        WriteFile.Output output = WriteFile.execute(new WriteFile.Input("hello.txt", "hi", workspace));

        assertThat(output.kind()).isEqualTo("create");
        assertThat(output.original_file()).isEmpty();
        assertThat(Files.readString(workspace.resolve("hello.txt"))).isEqualTo("hi");
    }

    @Test
    void updates_existing_file(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "old");
        WriteFile.Output output = WriteFile.execute(new WriteFile.Input("a.txt", "new", workspace));

        assertThat(output.kind()).isEqualTo("update");
        assertThat(output.original_file()).contains("old");
        assertThat(Files.readString(path)).isEqualTo("new");
    }

    @Test
    void creates_parent_directories(@TempDir Path workspace) throws IOException {
        WriteFile.execute(new WriteFile.Input("nested/deep/leaf.txt", "data", workspace));
        assertThat(Files.readString(workspace.resolve("nested/deep/leaf.txt"))).isEqualTo("data");
    }

    @Test
    void rejects_oversized_writes(@TempDir Path workspace) {
        String huge = "x".repeat(WriteFile.MAX_WRITE_SIZE + 1);
        assertThatThrownBy(() -> WriteFile.execute(new WriteFile.Input("a.txt", huge, workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.INVALID_DATA)
                .hasMessageContaining("too large");
    }

    @Test
    void rejects_path_traversal(@TempDir Path workspace, @TempDir Path other) {
        assertThatThrownBy(() -> WriteFile.execute(
                        new WriteFile.Input(other.resolve("escape.txt").toString(), "x", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED);
    }

    @Test
    void emits_structured_patch_with_diff_lines(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "old\nbody");
        WriteFile.Output output = WriteFile.execute(new WriteFile.Input("a.txt", "new\nbody", workspace));

        assertThat(output.structured_patch()).hasSize(1);
        StructuredPatchHunk hunk = output.structured_patch().get(0);
        assertThat(hunk.old_start()).isEqualTo(1);
        assertThat(hunk.new_start()).isEqualTo(1);
        assertThat(hunk.old_lines()).isEqualTo(2);
        assertThat(hunk.new_lines()).isEqualTo(2);
        assertThat(hunk.lines()).contains("-old", "-body", "+new", "+body");
    }

    @Test
    void atomic_write_does_not_leave_temp_file(@TempDir Path workspace) throws IOException {
        WriteFile.execute(new WriteFile.Input("a.txt", "data", workspace));
        try (var stream = Files.list(workspace)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".jclaude-write-") && name.endsWith(".tmp"));
        }
    }

    @Test
    void overwrites_with_empty_content(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "old");
        WriteFile.execute(new WriteFile.Input("a.txt", "", workspace));
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEmpty();
    }

    @Test
    void exact_max_size_write_is_allowed(@TempDir Path workspace) throws IOException {
        String exact = "x".repeat(WriteFile.MAX_WRITE_SIZE);
        WriteFile.Output output = WriteFile.execute(new WriteFile.Input("max.txt", exact, workspace));
        assertThat(output.kind()).isEqualTo("create");
        assertThat(Files.size(workspace.resolve("max.txt"))).isEqualTo(WriteFile.MAX_WRITE_SIZE);
    }
}
