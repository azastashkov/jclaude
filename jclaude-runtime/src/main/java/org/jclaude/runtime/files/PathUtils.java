package org.jclaude.runtime.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal utilities shared by the file-ops tools: path normalization, workspace boundary
 * enforcement, binary-content detection and shell-style brace expansion.
 */
final class PathUtils {

    private PathUtils() {}

    /**
     * Inspects the first 8 KiB of a file and reports whether any NUL byte was observed. NUL bytes
     * are the canonical heuristic for "binary content" used by ripgrep and the Rust source.
     */
    static boolean is_binary_file(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read = stream.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Resolves a path that must already exist. Mirrors {@code normalize_path} in the Rust source.
     * Relative paths resolve against the supplied workspace root; absolute paths are honored
     * as-is. The result is always a real, canonical, fully-resolved absolute path.
     */
    static Path normalize_path(String path, Path workspace_root) throws IOException {
        Path candidate = candidate_path(path, workspace_root);
        try {
            return candidate.toRealPath();
        } catch (NoSuchFileException error) {
            throw new FileOpsException(FileOpsException.Kind.NOT_FOUND, "no such file or directory: " + path, error);
        }
    }

    /**
     * Same as {@link #normalize_path(String, Path)} but tolerates a missing leaf component. Used
     * by {@code WriteFile} since the destination might not exist yet. Walks up the directory
     * chain until it finds an ancestor that does exist, canonicalizes that, then re-attaches the
     * missing tail. This keeps comparisons against the canonical workspace root sane on
     * platforms with symlinked temp dirs (e.g. {@code /var} → {@code /private/var} on macOS).
     */
    static Path normalize_path_allow_missing(String path, Path workspace_root) throws IOException {
        Path candidate = candidate_path(path, workspace_root).toAbsolutePath().normalize();
        try {
            return candidate.toRealPath();
        } catch (IOException ignored) {
            // fall through and resolve via the closest existing ancestor
        }

        Path existing = candidate;
        java.util.Deque<Path> tail = new java.util.ArrayDeque<>();
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) {
                tail.push(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return candidate;
        }
        Path canonical = existing.toRealPath();
        for (Path segment : tail) {
            canonical = canonical.resolve(segment);
        }
        return canonical;
    }

    /**
     * Validates that {@code resolved} stays within {@code workspace_root}. Mirrors the Rust
     * {@code validate_workspace_boundary} routine: refuse {@code ..} escapes and symlinks
     * pointing outside the workspace by checking that the canonical path starts with the
     * canonical workspace root.
     */
    static void validate_workspace_boundary(Path resolved, Path workspace_root) throws IOException {
        Path canonicalRoot = canonicalize_or_self(workspace_root);
        Path canonicalResolved = canonicalize_or_self(resolved);
        if (!canonicalResolved.startsWith(canonicalRoot)) {
            throw new FileOpsException(
                    FileOpsException.Kind.PERMISSION_DENIED,
                    "path " + resolved + " escapes workspace boundary " + workspace_root);
        }
    }

    /**
     * Reports whether {@code path} is a symlink whose target sits outside {@code workspace_root}.
     */
    static boolean is_symlink_escape(Path path, Path workspace_root) throws IOException {
        if (!Files.isSymbolicLink(path)) {
            return false;
        }
        Path resolved = path.toRealPath();
        Path canonicalRoot = canonicalize_or_self(workspace_root);
        return !resolved.startsWith(canonicalRoot);
    }

    /**
     * Expands a single level of shell-style brace alternation. {@code foo.{a,b,c}} becomes
     * {@code [foo.a, foo.b, foo.c]}. Patterns without braces (or with unmatched braces) pass
     * through untouched. Recursive on the trailing portion so {@code src/{a,b}.{rs,toml}}
     * works.
     */
    static List<String> expand_braces(String pattern) {
        int open = pattern.indexOf('{');
        if (open < 0) {
            return List.of(pattern);
        }
        int close = pattern.indexOf('}', open);
        if (close < 0) {
            return List.of(pattern);
        }
        String prefix = pattern.substring(0, open);
        String suffix = pattern.substring(close + 1);
        String alternatives = pattern.substring(open + 1, close);
        List<String> result = new ArrayList<>();
        for (String alt : alternatives.split(",", -1)) {
            result.addAll(expand_braces(prefix + alt + suffix));
        }
        return result;
    }

    private static Path candidate_path(String path, Path workspace_root) {
        Path raw = Paths.get(path);
        if (raw.isAbsolute()) {
            return raw;
        }
        return workspace_root.resolve(raw);
    }

    private static Path canonicalize_or_self(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Convenience accessor for {@link Files#exists(Path, LinkOption...)} that follows symlinks.
     */
    static boolean exists_following_links(Path path) {
        return Files.exists(path);
    }
}
