package org.jclaude.runtime.branch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Java port of {@code stale_base.rs}. */
public final class StaleBase {

    private StaleBase() {}

    public static Optional<String> read_claw_base_file(Path cwd) {
        Path path = cwd.resolve(".claw-base");
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String trimmed = content.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(trimmed);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<BaseCommitSource> resolve_expected_base(String flag_value, Path cwd) {
        if (flag_value != null) {
            String trimmed = flag_value.trim();
            if (!trimmed.isEmpty()) {
                return Optional.of(new BaseCommitSource.Flag(trimmed));
            }
        }
        return read_claw_base_file(cwd).map(BaseCommitSource.File::new);
    }

    public static BaseCommitState check_base_commit(Path cwd, BaseCommitSource expected_base) {
        if (expected_base == null) {
            return new BaseCommitState.NoExpectedBase();
        }
        String expected_raw = expected_base.value();

        Optional<String> head_sha = resolve_head_sha(cwd);
        if (head_sha.isEmpty()) {
            return new BaseCommitState.NotAGitRepo();
        }
        String head = head_sha.get();

        Optional<String> expected_sha = resolve_rev(cwd, expected_raw);
        if (expected_sha.isEmpty()) {
            if (head.startsWith(expected_raw) || expected_raw.startsWith(head)) {
                return new BaseCommitState.Matches();
            }
            return new BaseCommitState.Diverged(expected_raw, head);
        }

        if (head.equals(expected_sha.get())) {
            return new BaseCommitState.Matches();
        }
        return new BaseCommitState.Diverged(expected_sha.get(), head);
    }

    public static Optional<String> format_stale_base_warning(BaseCommitState state) {
        if (state instanceof BaseCommitState.Diverged d) {
            return Optional.of("warning: worktree HEAD (" + d.actual() + ") does not match expected base commit ("
                    + d.expected() + "). Session may run against a stale codebase.");
        }
        if (state instanceof BaseCommitState.NotAGitRepo) {
            return Optional.of("warning: stale-base check skipped — not inside a git repository.");
        }
        return Optional.empty();
    }

    private static Optional<String> resolve_head_sha(Path cwd) {
        return resolve_rev(cwd, "HEAD");
    }

    private static Optional<String> resolve_rev(Path cwd, String rev) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", rev);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                return Optional.empty();
            }
            String trimmed = stdout.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(trimmed);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
