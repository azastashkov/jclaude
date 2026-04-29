package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileTest {

    @Test
    void reads_full_file_when_no_offset_or_limit(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "one\ntwo\nthree");

        ReadFile.Output output = ReadFile.execute(ReadFile.Input.of("a.txt", workspace));

        assertThat(output.kind()).isEqualTo("text");
        assertThat(output.file().content()).isEqualTo("one\ntwo\nthree");
        assertThat(output.file().total_lines()).isEqualTo(3);
        assertThat(output.file().num_lines()).isEqualTo(3);
        assertThat(output.file().start_line()).isEqualTo(1);
    }

    @Test
    void reads_with_offset_and_limit(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "one\ntwo\nthree");

        ReadFile.Output output = ReadFile.execute(ReadFile.Input.of("a.txt", 1, 1, workspace));

        assertThat(output.file().content()).isEqualTo("two");
        assertThat(output.file().num_lines()).isEqualTo(1);
        assertThat(output.file().start_line()).isEqualTo(2);
        assertThat(output.file().total_lines()).isEqualTo(3);
    }

    @Test
    void reads_with_offset_past_end_returns_empty(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "one\ntwo");

        ReadFile.Output output = ReadFile.execute(ReadFile.Input.of("a.txt", 99, null, workspace));

        assertThat(output.file().content()).isEmpty();
        assertThat(output.file().num_lines()).isEqualTo(0);
        assertThat(output.file().start_line()).isEqualTo(3);
    }

    @Test
    void rejects_binary_files(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("bin.bin");
        Files.write(path, new byte[] {0x00, 0x01, 0x02, 0x03, 'b', 'i', 'n'});

        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of("bin.bin", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.INVALID_DATA)
                .hasMessageContaining("binary");
    }

    @Test
    void rejects_files_above_max_read_size(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("huge.txt");
        try (var channel = java.nio.channels.FileChannel.open(
                path,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.SPARSE)) {
            channel.position(ReadFile.MAX_READ_SIZE + 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {'x'}));
        }

        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of("huge.txt", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.INVALID_DATA)
                .hasMessageContaining("too large");
    }

    @Test
    void rejects_path_traversal_outside_workspace(@TempDir Path workspace, @TempDir Path other) throws IOException {
        Path outside = other.resolve("outside.txt");
        Files.writeString(outside, "secret");

        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of(outside.toString(), workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED);
    }

    @Test
    void rejects_relative_dotdot_traversal(@TempDir Path workspace) throws IOException {
        Path nested = workspace.resolve("nested");
        Files.createDirectory(nested);
        Path innerWorkspace = nested.resolve("inner");
        Files.createDirectory(innerWorkspace);
        Files.writeString(workspace.resolve("outside.txt"), "outside");

        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of("../../outside.txt", innerWorkspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED);
    }

    @Test
    void reports_missing_file_as_not_found(@TempDir Path workspace) {
        assertThatThrownBy(() -> ReadFile.execute(ReadFile.Input.of("nope.txt", workspace)))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.NOT_FOUND);
    }

    @Test
    void zero_limit_returns_no_lines(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("a.txt");
        Files.writeString(path, "x\ny\nz");
        ReadFile.Output output = ReadFile.execute(ReadFile.Input.of("a.txt", 0, 0, workspace));
        assertThat(output.file().content()).isEmpty();
        assertThat(output.file().num_lines()).isEqualTo(0);
    }
}
