package org.jclaude.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves filesystem paths inside the upstream Claude Code repository. Mirrors Rust's
 * {@code UpstreamPaths}.
 */
public record UpstreamPaths(Path repo_root) {

    public static UpstreamPaths from_repo_root(Path repo_root) {
        return new UpstreamPaths(repo_root);
    }

    public static UpstreamPaths from_workspace_dir(Path workspace_dir) {
        Path canonical;
        try {
            canonical = workspace_dir.toRealPath();
        } catch (IOException e) {
            canonical = workspace_dir;
        }
        Path primary_repo_root = canonical.getParent() == null ? Paths.get("..") : canonical.getParent();
        Path repo_root = resolve_upstream_repo_root(primary_repo_root);
        return new UpstreamPaths(repo_root);
    }

    public Path commands_path() {
        return repo_root.resolve("src/commands.ts");
    }

    public Path tools_path() {
        return repo_root.resolve("src/tools.ts");
    }

    public Path cli_path() {
        return repo_root.resolve("src/entrypoints/cli.tsx");
    }

    private static Path resolve_upstream_repo_root(Path primary_repo_root) {
        for (Path candidate : upstream_repo_candidates(primary_repo_root)) {
            if (Files.isRegularFile(candidate.resolve("src/commands.ts"))) {
                return candidate;
            }
        }
        return primary_repo_root;
    }

    private static List<Path> upstream_repo_candidates(Path primary_repo_root) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(primary_repo_root);
        String explicit = System.getenv("CLAUDE_CODE_UPSTREAM");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit));
        }
        Path ancestor = primary_repo_root;
        for (int i = 0; i < 4 && ancestor != null; i++, ancestor = ancestor.getParent()) {
            candidates.add(ancestor.resolve("claw-code"));
            candidates.add(ancestor.resolve("clawd-code"));
        }
        candidates.add(primary_repo_root.resolve("reference-source").resolve("claw-code"));
        candidates.add(primary_repo_root.resolve("vendor").resolve("claw-code"));
        List<Path> deduped = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!deduped.contains(candidate)) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }
}
