package org.jclaude.runtime.files;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds files matching a glob pattern. Supports shell-style brace expansion via
 * {@link PathUtils#expand_braces(String)}, sorts hits by mtime descending, and caps the result at
 * 100 entries with a truncated flag.
 *
 * <p>Patterns are matched against {@link Path}s relative to the search base. Absolute patterns
 * have the base directory stripped before matching to keep the matcher symlink-agnostic
 * (important on macOS where {@code /var} resolves to {@code /private/var}).
 */
public final class GlobSearch {

    private static final int MAX_RESULTS = 100;
    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(".git", "node_modules", "target", "build");

    private GlobSearch() {}

    public record Input(String pattern, Optional<String> path, Path workspace_root) {

        public Input {
            if (pattern == null) {
                throw new IllegalArgumentException("pattern must not be null");
            }
            if (path == null) {
                path = Optional.empty();
            }
            if (workspace_root == null) {
                throw new IllegalArgumentException("workspace_root must not be null");
            }
        }

        public static Input of(String pattern, String path, Path workspace_root) {
            return new Input(pattern, Optional.ofNullable(path), workspace_root);
        }
    }

    public record Output(long duration_ms, int num_files, List<String> filenames, boolean truncated) {}

    public static Output execute(Input input) throws IOException {
        long started = System.currentTimeMillis();
        Path baseDir = resolve_base_dir(input);

        List<String> patterns = PathUtils.expand_braces(input.pattern());
        Set<Path> seen = new LinkedHashSet<>();

        for (String pattern : patterns) {
            String relativePattern;
            Path searchRoot;
            if (Paths.get(pattern).isAbsolute()) {
                searchRoot = baseDir;
                String basePrefix = baseDir.toString();
                if (pattern.startsWith(basePrefix)) {
                    relativePattern = pattern.substring(basePrefix.length()).replaceAll("^[/\\\\]+", "");
                } else {
                    relativePattern = pattern;
                }
            } else {
                searchRoot = baseDir;
                relativePattern = pattern;
            }

            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + relativePattern);
            } catch (IllegalArgumentException error) {
                throw new FileOpsException(FileOpsException.Kind.INVALID_INPUT, error.getMessage(), error);
            }
            collect_matches(searchRoot, matcher, seen);
        }

        List<Path> matches = new ArrayList<>(seen);
        matches.sort(Comparator.comparing(GlobSearch::mtime_or_min, Comparator.reverseOrder()));

        boolean truncated = matches.size() > MAX_RESULTS;
        if (truncated) {
            matches = matches.subList(0, MAX_RESULTS);
        }
        List<String> filenames = matches.stream().map(Path::toString).toList();

        long duration = System.currentTimeMillis() - started;
        return new Output(duration, filenames.size(), filenames, truncated);
    }

    private static Path resolve_base_dir(Input input) throws IOException {
        if (input.path().isPresent()) {
            return PathUtils.normalize_path(input.path().get(), input.workspace_root());
        }
        return input.workspace_root().toRealPath();
    }

    private static void collect_matches(Path searchRoot, PathMatcher matcher, Set<Path> seen) throws IOException {
        if (!Files.exists(searchRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(searchRoot)) {
            stream.filter(p -> !is_in_excluded_dir(searchRoot, p))
                    .filter(Files::isRegularFile)
                    .filter(p -> match_relative(searchRoot, p, matcher))
                    .forEach(seen::add);
        }
    }

    private static boolean match_relative(Path searchRoot, Path candidate, PathMatcher matcher) {
        Path relative;
        try {
            relative = searchRoot.relativize(candidate);
        } catch (IllegalArgumentException ignored) {
            relative = candidate.getFileName();
        }
        return matcher.matches(relative) || matcher.matches(candidate);
    }

    private static boolean is_in_excluded_dir(Path searchRoot, Path candidate) {
        Path relative;
        try {
            relative = searchRoot.relativize(candidate);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        for (Path part : relative) {
            if (EXCLUDED_DIR_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static FileTime mtime_or_min(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0);
        }
    }
}
