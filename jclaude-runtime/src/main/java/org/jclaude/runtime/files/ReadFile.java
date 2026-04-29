package org.jclaude.runtime.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads a UTF-8 text file with optional offset/limit windowing. Refuses files larger than
 * {@link #MAX_READ_SIZE} bytes or files containing NUL bytes (binary heuristic).
 */
public final class ReadFile {

    /** Maximum file size that can be read (10 MiB). */
    public static final long MAX_READ_SIZE = 10L * 1024 * 1024;

    private ReadFile() {}

    public record Input(String path, Optional<Integer> offset, Optional<Integer> limit, Path workspace_root) {

        public Input {
            if (path == null) {
                throw new IllegalArgumentException("path must not be null");
            }
            if (offset == null) {
                offset = Optional.empty();
            }
            if (limit == null) {
                limit = Optional.empty();
            }
            if (workspace_root == null) {
                throw new IllegalArgumentException("workspace_root must not be null");
            }
        }

        public static Input of(String path, Path workspace_root) {
            return new Input(path, Optional.empty(), Optional.empty(), workspace_root);
        }

        public static Input of(String path, Integer offset, Integer limit, Path workspace_root) {
            return new Input(path, Optional.ofNullable(offset), Optional.ofNullable(limit), workspace_root);
        }
    }

    public record Output(String kind, TextFilePayload file) {}

    public static Output execute(Input input) throws IOException {
        Path absolute_path = PathUtils.normalize_path(input.path(), input.workspace_root());
        PathUtils.validate_workspace_boundary(absolute_path, input.workspace_root());

        long size = Files.size(absolute_path);
        if (size > MAX_READ_SIZE) {
            throw new FileOpsException(
                    FileOpsException.Kind.INVALID_DATA,
                    "file is too large (" + size + " bytes, max " + MAX_READ_SIZE + " bytes)");
        }

        if (PathUtils.is_binary_file(absolute_path)) {
            throw new FileOpsException(FileOpsException.Kind.INVALID_DATA, "file appears to be binary");
        }

        String content = Files.readString(absolute_path);
        List<String> lines = Patches.split_lines(content);
        int totalLines = lines.size();
        int requestedOffset = input.offset().orElse(0);
        int startIndex = Math.max(0, Math.min(requestedOffset, totalLines));
        int endIndex;
        if (input.limit().isPresent()) {
            int limit = Math.max(0, input.limit().get());
            long candidate = (long) startIndex + (long) limit;
            endIndex = (int) Math.min(candidate, totalLines);
        } else {
            endIndex = totalLines;
        }

        StringBuilder selected = new StringBuilder();
        for (int i = startIndex; i < endIndex; i++) {
            if (i > startIndex) {
                selected.append('\n');
            }
            selected.append(lines.get(i));
        }

        TextFilePayload payload = new TextFilePayload(
                absolute_path.toString(),
                selected.toString(),
                Math.max(0, endIndex - startIndex),
                startIndex + 1,
                totalLines);
        return new Output("text", payload);
    }
}
