package org.jclaude.runtime.branch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stale-branch helper mirroring the Rust functions. */
public final class StaleBranch {

    private StaleBranch() {}

    public static BranchFreshness check_freshness(String branch, String main_ref) {
        return check_freshness_in(branch, main_ref, Path.of("."));
    }

    public static BranchFreshness check_freshness_in(String branch, String main_ref, Path repo_path) {
        int behind = rev_list_count(main_ref, branch, repo_path);
        int ahead = rev_list_count(branch, main_ref, repo_path);

        if (behind == 0) {
            return new BranchFreshness.Fresh();
        }

        List<String> missing_fixes = missing_fix_subjects(main_ref, branch, repo_path);

        if (ahead > 0) {
            return new BranchFreshness.Diverged(ahead, behind, missing_fixes);
        }

        return new BranchFreshness.Stale(behind, missing_fixes);
    }

    public static StaleBranchAction apply_policy(BranchFreshness freshness, StaleBranchPolicy policy) {
        if (freshness instanceof BranchFreshness.Fresh) {
            return new StaleBranchAction.Noop();
        }
        if (freshness instanceof BranchFreshness.Stale s) {
            return switch (policy) {
                case WARN_ONLY -> new StaleBranchAction.Warn("Branch is " + s.commits_behind()
                        + " commit(s) behind main. Missing fixes: " + format_missing_fixes(s.missing_fixes()));
                case BLOCK -> new StaleBranchAction.Block("Branch is " + s.commits_behind()
                        + " commit(s) behind main and must be updated before proceeding.");
                case AUTO_REBASE -> new StaleBranchAction.Rebase();
                case AUTO_MERGE_FORWARD -> new StaleBranchAction.MergeForward();
            };
        }
        if (freshness instanceof BranchFreshness.Diverged d) {
            return switch (policy) {
                case WARN_ONLY -> new StaleBranchAction.Warn(
                        "Branch has diverged: " + d.ahead() + " commit(s) ahead, " + d.behind()
                                + " commit(s) behind main. Missing fixes: " + format_missing_fixes(d.missing_fixes()));
                case BLOCK -> new StaleBranchAction.Block("Branch has diverged (" + d.ahead() + " ahead, " + d.behind()
                        + " behind) and must be reconciled before proceeding. Missing fixes: "
                        + format_missing_fixes(d.missing_fixes()));
                case AUTO_REBASE -> new StaleBranchAction.Rebase();
                case AUTO_MERGE_FORWARD -> new StaleBranchAction.MergeForward();
            };
        }
        throw new IllegalStateException("unreachable");
    }

    private static String format_missing_fixes(List<String> missing_fixes) {
        if (missing_fixes.isEmpty()) {
            return "(none)";
        }
        return String.join("; ", missing_fixes);
    }

    private static int rev_list_count(String a, String b, Path repo_path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-list", "--count", b + ".." + a);
            pb.directory(repo_path.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                return 0;
            }
            try {
                return Integer.parseInt(stdout.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }

    private static List<String> missing_fix_subjects(String a, String b, Path repo_path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "--format=%s", b + ".." + a);
            pb.directory(repo_path.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String line : stdout.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }
}
