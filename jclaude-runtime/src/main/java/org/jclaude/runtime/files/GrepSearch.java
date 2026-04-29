package org.jclaude.runtime.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Regex-search across a workspace tree. Defaults match ripgrep behavior: skip binary files, skip
 * the noise directories listed in {@link #DEFAULT_EXCLUDED_DIRS}, return one path per file with
 * a match.
 */
public final class GrepSearch {

    /** Recognized values for {@link Input#output_mode()}. */
    public static final String MODE_CONTENT = "content";

    public static final String MODE_FILES_WITH_MATCHES = "files_with_matches";
    public static final String MODE_COUNT = "count";

    private static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(".git", "node_modules", "target", "build");

    private GrepSearch() {}

    public record Input(String pattern, String path, Optional<String> output_mode, Path workspace_root) {

        public Input {
            if (pattern == null) {
                throw new IllegalArgumentException("pattern must not be null");
            }
            if (path == null) {
                throw new IllegalArgumentException("path must not be null");
            }
            if (output_mode == null) {
                output_mode = Optional.empty();
            }
            if (workspace_root == null) {
                throw new IllegalArgumentException("workspace_root must not be null");
            }
        }

        public static Input of(String pattern, String path, Path workspace_root) {
            return new Input(pattern, path, Optional.empty(), workspace_root);
        }

        public static Input of(String pattern, String path, String outputMode, Path workspace_root) {
            return new Input(pattern, path, Optional.ofNullable(outputMode), workspace_root);
        }
    }

    public record Output(
            String mode,
            int num_files,
            List<String> filenames,
            Optional<String> content,
            Optional<Integer> num_lines,
            Optional<Integer> num_matches) {}

    public static Output execute(Input input) throws IOException {
        Pattern regex;
        try {
            regex = Pattern.compile(input.pattern());
        } catch (PatternSyntaxException error) {
            throw new FileOpsException(FileOpsException.Kind.INVALID_INPUT, error.getMessage(), error);
        }

        Path basePath = PathUtils.normalize_path(input.path(), input.workspace_root());
        PathUtils.validate_workspace_boundary(basePath, input.workspace_root());

        String outputMode = input.output_mode().orElse(MODE_FILES_WITH_MATCHES);

        List<String> filenames = new ArrayList<>();
        List<String> contentLines = new ArrayList<>();
        int totalMatches = 0;

        for (Path file : collect_search_files(basePath)) {
            String fileContents;
            try {
                if (PathUtils.is_binary_file(file)) {
                    continue;
                }
                fileContents = Files.readString(file);
            } catch (IOException ignored) {
                continue;
            }

            if (MODE_COUNT.equals(outputMode)) {
                int count = (int) regex.matcher(fileContents).results().count();
                if (count > 0) {
                    filenames.add(file.toString());
                    totalMatches += count;
                }
                continue;
            }

            List<String> lines = Patches.split_lines(fileContents);
            List<Integer> matchedLineIndexes = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    totalMatches++;
                    matchedLineIndexes.add(i);
                }
            }
            if (matchedLineIndexes.isEmpty()) {
                continue;
            }
            filenames.add(file.toString());
            if (MODE_CONTENT.equals(outputMode)) {
                for (int index : matchedLineIndexes) {
                    contentLines.add(file + ":" + (index + 1) + ":" + lines.get(index));
                }
            }
        }

        if (MODE_CONTENT.equals(outputMode)) {
            return new Output(
                    outputMode,
                    filenames.size(),
                    filenames,
                    Optional.of(String.join("\n", contentLines)),
                    Optional.of(contentLines.size()),
                    Optional.empty());
        }
        if (MODE_COUNT.equals(outputMode)) {
            return new Output(
                    outputMode,
                    filenames.size(),
                    filenames,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(totalMatches));
        }
        return new Output(
                outputMode, filenames.size(), filenames, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static List<Path> collect_search_files(Path basePath) throws IOException {
        if (Files.isRegularFile(basePath)) {
            return List.of(basePath);
        }
        if (!Files.isDirectory(basePath)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(basePath)) {
            stream.filter(GrepSearch::not_excluded_dir)
                    .filter(Files::isRegularFile)
                    .forEach(result::add);
        }
        return result;
    }

    private static boolean not_excluded_dir(Path path) {
        for (Path part : path) {
            if (DEFAULT_EXCLUDED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }
}
