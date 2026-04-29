package org.jclaude.runtime.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaleBranchTest {

    private static void run(Path cwd, String... args) throws IOException, InterruptedException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed with " + exit);
        }
    }

    private static void init_repo(Path path) throws IOException, InterruptedException {
        Files.createDirectories(path);
        run(path, "init", "--quiet", "-b", "main");
        run(path, "config", "user.email", "tests@example.com");
        run(path, "config", "user.name", "Stale Branch Tests");
        Files.writeString(path.resolve("init.txt"), "initial\n");
        run(path, "add", ".");
        run(path, "commit", "-m", "initial commit", "--quiet");
    }

    private static void commit_file(Path repo, String name, String msg) throws IOException, InterruptedException {
        Files.writeString(repo.resolve(name), msg + "\n");
        run(repo, "add", name);
        run(repo, "commit", "-m", msg, "--quiet");
    }

    @Test
    void fresh_branch_passes(@TempDir Path root) throws Exception {
        init_repo(root);
        run(root, "checkout", "-b", "topic");

        BranchFreshness freshness = StaleBranch.check_freshness_in("topic", "main", root);

        assertThat(freshness).isInstanceOf(BranchFreshness.Fresh.class);
    }

    @Test
    void fresh_branch_ahead_of_main_still_fresh(@TempDir Path root) throws Exception {
        init_repo(root);
        run(root, "checkout", "-b", "topic");
        commit_file(root, "feature.txt", "add feature");

        BranchFreshness freshness = StaleBranch.check_freshness_in("topic", "main", root);

        assertThat(freshness).isInstanceOf(BranchFreshness.Fresh.class);
    }

    @Test
    void stale_branch_detected_with_correct_behind_count_and_missing_fixes(@TempDir Path root) throws Exception {
        init_repo(root);
        run(root, "checkout", "-b", "topic");
        run(root, "checkout", "main");
        commit_file(root, "fix1.txt", "fix: resolve timeout");
        commit_file(root, "fix2.txt", "fix: handle null pointer");

        BranchFreshness freshness = StaleBranch.check_freshness_in("topic", "main", root);

        assertThat(freshness).isInstanceOf(BranchFreshness.Stale.class);
        BranchFreshness.Stale stale = (BranchFreshness.Stale) freshness;
        assertThat(stale.commits_behind()).isEqualTo(2);
        assertThat(stale.missing_fixes()).hasSize(2);
        assertThat(stale.missing_fixes().get(0)).isEqualTo("fix: handle null pointer");
        assertThat(stale.missing_fixes().get(1)).isEqualTo("fix: resolve timeout");
    }

    @Test
    void diverged_branch_detection(@TempDir Path root) throws Exception {
        init_repo(root);
        run(root, "checkout", "-b", "topic");
        commit_file(root, "topic_work.txt", "topic work");
        run(root, "checkout", "main");
        commit_file(root, "main_fix.txt", "main fix");

        BranchFreshness freshness = StaleBranch.check_freshness_in("topic", "main", root);

        assertThat(freshness).isInstanceOf(BranchFreshness.Diverged.class);
        BranchFreshness.Diverged d = (BranchFreshness.Diverged) freshness;
        assertThat(d.ahead()).isEqualTo(1);
        assertThat(d.behind()).isEqualTo(1);
        assertThat(d.missing_fixes()).containsExactly("main fix");
    }

    @Test
    void policy_noop_for_fresh_branch() {
        BranchFreshness freshness = new BranchFreshness.Fresh();

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.WARN_ONLY);

        assertThat(action).isInstanceOf(StaleBranchAction.Noop.class);
    }

    @Test
    void policy_warn_for_stale_branch() {
        BranchFreshness freshness = new BranchFreshness.Stale(3, List.of("fix: timeout", "fix: null ptr"));

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.WARN_ONLY);

        assertThat(action).isInstanceOf(StaleBranchAction.Warn.class);
        StaleBranchAction.Warn warn = (StaleBranchAction.Warn) action;
        assertThat(warn.message()).contains("3 commit(s) behind");
        assertThat(warn.message()).contains("fix: timeout");
        assertThat(warn.message()).contains("fix: null ptr");
    }

    @Test
    void policy_block_for_stale_branch() {
        BranchFreshness freshness = new BranchFreshness.Stale(1, List.of("hotfix"));

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.BLOCK);

        assertThat(action).isInstanceOf(StaleBranchAction.Block.class);
        assertThat(((StaleBranchAction.Block) action).message()).contains("1 commit(s) behind");
    }

    @Test
    void policy_auto_rebase_for_stale_branch() {
        BranchFreshness freshness = new BranchFreshness.Stale(2, List.of());

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.AUTO_REBASE);

        assertThat(action).isInstanceOf(StaleBranchAction.Rebase.class);
    }

    @Test
    void policy_auto_merge_forward_for_diverged_branch() {
        BranchFreshness freshness = new BranchFreshness.Diverged(5, 2, List.of("fix: merge main"));

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.AUTO_MERGE_FORWARD);

        assertThat(action).isInstanceOf(StaleBranchAction.MergeForward.class);
    }

    @Test
    void policy_warn_for_diverged_branch() {
        BranchFreshness freshness = new BranchFreshness.Diverged(3, 1, List.of("main hotfix"));

        StaleBranchAction action = StaleBranch.apply_policy(freshness, StaleBranchPolicy.WARN_ONLY);

        assertThat(action).isInstanceOf(StaleBranchAction.Warn.class);
        StaleBranchAction.Warn warn = (StaleBranchAction.Warn) action;
        assertThat(warn.message()).contains("diverged");
        assertThat(warn.message()).contains("3 commit(s) ahead");
        assertThat(warn.message()).contains("1 commit(s) behind");
        assertThat(warn.message()).contains("main hotfix");
    }
}
