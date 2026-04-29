package org.jclaude.runtime.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaleBaseTest {

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
        run(path, "config", "user.name", "Stale Base Tests");
        Files.writeString(path.resolve("init.txt"), "initial\n");
        run(path, "add", ".");
        run(path, "commit", "-m", "initial commit", "--quiet");
    }

    private static void commit_file(Path repo, String name, String msg) throws IOException, InterruptedException {
        Files.writeString(repo.resolve(name), msg + "\n");
        run(repo, "add", name);
        run(repo, "commit", "-m", msg, "--quiet");
    }

    private static String head_sha(Path repo) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.directory(repo.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out.trim();
    }

    @Test
    void matches_when_head_equals_expected_base(@TempDir Path root) throws Exception {
        init_repo(root);
        String sha = head_sha(root);
        BaseCommitSource source = new BaseCommitSource.Flag(sha);

        BaseCommitState state = StaleBase.check_base_commit(root, source);

        assertThat(state).isInstanceOf(BaseCommitState.Matches.class);
    }

    @Test
    void diverged_when_head_moved_past_expected_base(@TempDir Path root) throws Exception {
        init_repo(root);
        String old_sha = head_sha(root);
        commit_file(root, "extra.txt", "move head forward");
        String new_sha = head_sha(root);
        BaseCommitSource source = new BaseCommitSource.Flag(old_sha);

        BaseCommitState state = StaleBase.check_base_commit(root, source);

        assertThat(state).isEqualTo(new BaseCommitState.Diverged(old_sha, new_sha));
    }

    @Test
    void no_expected_base_when_source_is_none(@TempDir Path root) throws Exception {
        init_repo(root);

        BaseCommitState state = StaleBase.check_base_commit(root, null);

        assertThat(state).isInstanceOf(BaseCommitState.NoExpectedBase.class);
    }

    @Test
    void not_a_git_repo_when_outside_repo(@TempDir Path root) {
        BaseCommitSource source = new BaseCommitSource.Flag("abc1234");

        BaseCommitState state = StaleBase.check_base_commit(root, source);

        assertThat(state).isInstanceOf(BaseCommitState.NotAGitRepo.class);
    }

    @Test
    void reads_claw_base_file(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".claw-base"), "abc1234def5678\n");

        Optional<String> value = StaleBase.read_claw_base_file(root);

        assertThat(value).contains("abc1234def5678");
    }

    @Test
    void returns_none_for_missing_claw_base_file(@TempDir Path root) {
        Optional<String> value = StaleBase.read_claw_base_file(root);

        assertThat(value).isEmpty();
    }

    @Test
    void returns_none_for_empty_claw_base_file(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".claw-base"), "  \n");

        Optional<String> value = StaleBase.read_claw_base_file(root);

        assertThat(value).isEmpty();
    }

    @Test
    void resolve_expected_base_prefers_flag_over_file(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".claw-base"), "from_file\n");

        Optional<BaseCommitSource> source = StaleBase.resolve_expected_base("from_flag", root);

        assertThat(source).contains(new BaseCommitSource.Flag("from_flag"));
    }

    @Test
    void resolve_expected_base_falls_back_to_file(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".claw-base"), "from_file\n");

        Optional<BaseCommitSource> source = StaleBase.resolve_expected_base(null, root);

        assertThat(source).contains(new BaseCommitSource.File("from_file"));
    }

    @Test
    void resolve_expected_base_returns_none_when_nothing_available(@TempDir Path root) {
        Optional<BaseCommitSource> source = StaleBase.resolve_expected_base(null, root);

        assertThat(source).isEmpty();
    }

    @Test
    void format_warning_returns_message_for_diverged() {
        BaseCommitState state = new BaseCommitState.Diverged("abc1234", "def5678");

        Optional<String> warning = StaleBase.format_stale_base_warning(state);

        assertThat(warning).isPresent();
        assertThat(warning.get()).contains("abc1234");
        assertThat(warning.get()).contains("def5678");
        assertThat(warning.get()).contains("stale codebase");
    }

    @Test
    void format_warning_returns_none_for_matches() {
        BaseCommitState state = new BaseCommitState.Matches();

        Optional<String> warning = StaleBase.format_stale_base_warning(state);

        assertThat(warning).isEmpty();
    }

    @Test
    void format_warning_returns_none_for_no_expected_base() {
        BaseCommitState state = new BaseCommitState.NoExpectedBase();

        Optional<String> warning = StaleBase.format_stale_base_warning(state);

        assertThat(warning).isEmpty();
    }

    @Test
    void matches_with_claw_base_file_in_real_repo(@TempDir Path root) throws Exception {
        init_repo(root);
        String sha = head_sha(root);
        Files.writeString(root.resolve(".claw-base"), sha + "\n");
        Optional<BaseCommitSource> source = StaleBase.resolve_expected_base(null, root);

        BaseCommitState state = StaleBase.check_base_commit(root, source.orElse(null));

        assertThat(state).isInstanceOf(BaseCommitState.Matches.class);
    }

    @Test
    void diverged_with_claw_base_file_after_new_commit(@TempDir Path root) throws Exception {
        init_repo(root);
        String old_sha = head_sha(root);
        Files.writeString(root.resolve(".claw-base"), old_sha + "\n");
        commit_file(root, "new.txt", "advance head");
        String new_sha = head_sha(root);
        Optional<BaseCommitSource> source = StaleBase.resolve_expected_base(null, root);

        BaseCommitState state = StaleBase.check_base_commit(root, source.orElse(null));

        assertThat(state).isEqualTo(new BaseCommitState.Diverged(old_sha, new_sha));
    }
}
