package org.jclaude.runtime.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Performs a literal-string in-place edit. By default the {@code old_string} must occur exactly
 * once in the file; passing {@code replace_all = true} relaxes that constraint and replaces every
 * occurrence.
 */
public final class EditFile {

    private EditFile() {}

    public record Input(String path, String old_string, String new_string, boolean replace_all, Path workspace_root) {

        public Input {
            if (path == null) {
                throw new IllegalArgumentException("path must not be null");
            }
            if (old_string == null) {
                throw new IllegalArgumentException("old_string must not be null");
            }
            if (new_string == null) {
                throw new IllegalArgumentException("new_string must not be null");
            }
            if (workspace_root == null) {
                throw new IllegalArgumentException("workspace_root must not be null");
            }
        }

        public static Input of(String path, String oldString, String newString, Path workspaceRoot) {
            return new Input(path, oldString, newString, false, workspaceRoot);
        }
    }

    public record Output(
            String file_path,
            String old_string,
            String new_string,
            String original_file,
            List<StructuredPatchHunk> structured_patch,
            boolean user_modified,
            boolean replace_all) {}

    public static Output execute(Input input) throws IOException {
        Path absolute_path = PathUtils.normalize_path(input.path(), input.workspace_root());
        PathUtils.validate_workspace_boundary(absolute_path, input.workspace_root());
        String original_file = Files.readString(absolute_path);
        if (input.old_string().equals(input.new_string())) {
            throw new FileOpsException(FileOpsException.Kind.INVALID_INPUT, "old_string and new_string must differ");
        }
        if (!original_file.contains(input.old_string())) {
            throw new FileOpsException(FileOpsException.Kind.NOT_FOUND, "old_string not found in file");
        }

        String updated;
        if (input.replace_all()) {
            updated = original_file.replace(input.old_string(), input.new_string());
        } else {
            int index = original_file.indexOf(input.old_string());
            updated = original_file.substring(0, index)
                    + input.new_string()
                    + original_file.substring(index + input.old_string().length());
        }
        Files.writeString(absolute_path, updated);

        return new Output(
                absolute_path.toString(),
                input.old_string(),
                input.new_string(),
                original_file,
                Patches.make_patch(original_file, updated),
                false,
                input.replace_all());
    }
}
