package org.jclaude.runtime.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Writes a UTF-8 text file. The write is performed atomically via a sibling temp file plus an
 * {@link Files#move} with {@link StandardCopyOption#ATOMIC_MOVE} when the platform supports it,
 * falling back to a non-atomic move otherwise.
 */
public final class WriteFile {

    /** Maximum content length accepted by a single write (10 MiB). */
    public static final int MAX_WRITE_SIZE = 10 * 1024 * 1024;

    private WriteFile() {}

    public record Input(String path, String content, Path workspace_root) {

        public Input {
            if (path == null) {
                throw new IllegalArgumentException("path must not be null");
            }
            if (content == null) {
                throw new IllegalArgumentException("content must not be null");
            }
            if (workspace_root == null) {
                throw new IllegalArgumentException("workspace_root must not be null");
            }
        }
    }

    public record Output(
            String kind,
            String file_path,
            String content,
            List<StructuredPatchHunk> structured_patch,
            Optional<String> original_file) {}

    public static Output execute(Input input) throws IOException {
        byte[] bytes = input.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_SIZE) {
            throw new FileOpsException(
                    FileOpsException.Kind.INVALID_DATA,
                    "content is too large (" + bytes.length + " bytes, max " + MAX_WRITE_SIZE + " bytes)");
        }

        Path absolute_path = PathUtils.normalize_path_allow_missing(input.path(), input.workspace_root());
        PathUtils.validate_workspace_boundary(absolute_path, input.workspace_root());

        Optional<String> originalFile = Optional.empty();
        if (Files.exists(absolute_path)) {
            try {
                originalFile = Optional.of(Files.readString(absolute_path));
            } catch (IOException ignored) {
                // Existing file is unreadable (binary, perms, etc.). Treat as create.
            }
        }

        Path parent = absolute_path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        atomic_write(absolute_path, bytes);

        return new Output(
                originalFile.isPresent() ? "update" : "create",
                absolute_path.toString(),
                input.content(),
                Patches.make_patch(originalFile.orElse(""), input.content()),
                originalFile);
    }

    private static void atomic_write(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        Path tempFile =
                Files.createTempFile(parent != null ? parent : target.toAbsolutePath(), ".jclaude-write-", ".tmp");
        try {
            Files.write(tempFile, bytes);
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException | IOException ignored) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
