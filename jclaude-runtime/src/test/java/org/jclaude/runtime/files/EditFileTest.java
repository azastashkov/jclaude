package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditFileTest {

    @Test
    void replaces_single_match(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "alpha bravo charlie");
        EditFile.Output output = EditFile.execute(EditFile.Input.of("a.txt", "bravo", "delta", workspace));

        assertThat(output.replace_all()).isFalse();
        assertThat(output.user_modified()).isFalse();
        assertThat(Files.readString(path)).isEqualTo("alpha delta charlie");
    }

    @Test
    void replaces_all_occurrences_when_flag_set(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "alpha alpha alpha");
        EditFile.Output output = EditFile.execute(new EditFile.Input("a.txt", "alpha", "omega", true, workspace));

        assertThat(output.replace_all()).isTrue();
        assertThat(Files.readString(path)).isEqualTo("omega omega omega");
    }

    @Test
    void replaces_first_match_when_replace_all_is_false_with_multiple(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "x x x");
        EditFile.execute(EditFile.Input.of("a.txt", "x", "y", workspace));

        assertThat(Files.readString(path)).isEqualTo("y x x");
    }

    @Test
    void rejects_when_old_and_new_are_equal(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "alpha");

        assertThatThrownBy(() -> EditFile.execute(EditFile.Input.of("a.txt", "alpha", "alpha", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.INVALID_INPUT);
    }

    @Test
    void rejects_when_old_string_not_found(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "alpha");

        assertThatThrownBy(() -> EditFile.execute(EditFile.Input.of("a.txt", "missing", "delta", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.NOT_FOUND);
    }

    @Test
    void rejects_path_traversal(@TempDir Path workspace, @TempDir Path other) throws IOException {
        Path outside = other.resolve("escape.txt");
        Files.writeString(outside, "old");

        assertThatThrownBy(() -> EditFile.execute(EditFile.Input.of(outside.toString(), "old", "new", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED);
    }

    @Test
    void records_original_file_in_output(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "before");
        EditFile.Output output = EditFile.execute(EditFile.Input.of("a.txt", "before", "after", workspace));

        assertThat(output.original_file()).isEqualTo("before");
    }

    @Test
    void emits_structured_patch_summary(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "alpha\nbeta");
        EditFile.Output output = EditFile.execute(EditFile.Input.of("a.txt", "alpha", "omega", workspace));

        assertThat(output.structured_patch()).hasSize(1);
        StructuredPatchHunk hunk = output.structured_patch().get(0);
        assertThat(hunk.lines()).contains("-alpha", "-beta", "+omega", "+beta");
    }

    @Test
    void multi_line_old_and_new_strings_are_supported(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "head\nold-1\nold-2\ntail");
        EditFile.execute(EditFile.Input.of("a.txt", "old-1\nold-2", "new-1\nnew-2", workspace));

        assertThat(Files.readString(path)).isEqualTo("head\nnew-1\nnew-2\ntail");
    }
}
