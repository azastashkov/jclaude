package org.jclaude.runtime.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitContextTest {

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
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
            throw new IOException("git " + String.join(" ", args) + " failed");
        }
    }

    @Test
    void returns_none_for_non_git_directory(@TempDir Path root) {
        Optional<GitContext> context = GitContext.detect(root);

        assertThat(context).isEmpty();
    }

    @Test
    void detects_branch_name_and_commits(@TempDir Path root) throws Exception {
        git(root, "init", "--quiet", "--initial-branch=main");
        git(root, "config", "user.email", "tests@example.com");
        git(root, "config", "user.name", "Git Context Tests");
        Files.writeString(root.resolve("a.txt"), "a\n");
        git(root, "add", "a.txt");
        git(root, "commit", "-m", "first commit", "--quiet");
        Files.writeString(root.resolve("b.txt"), "b\n");
        git(root, "add", "b.txt");
        git(root, "commit", "-m", "second commit", "--quiet");

        GitContext context = GitContext.detect(root).orElseThrow();

        assertThat(context.branch()).contains("main");
        assertThat(context.recent_commits()).hasSize(2);
        assertThat(context.recent_commits().get(0).subject()).isEqualTo("second commit");
        assertThat(context.recent_commits().get(1).subject()).isEqualTo("first commit");
        assertThat(context.staged_files()).isEmpty();
    }

    @Test
    void detects_staged_files(@TempDir Path root) throws Exception {
        git(root, "init", "--quiet", "--initial-branch=main");
        git(root, "config", "user.email", "tests@example.com");
        git(root, "config", "user.name", "Git Context Tests");
        Files.writeString(root.resolve("init.txt"), "init\n");
        git(root, "add", "init.txt");
        git(root, "commit", "-m", "initial", "--quiet");
        Files.writeString(root.resolve("staged.txt"), "staged\n");
        git(root, "add", "staged.txt");

        GitContext context = GitContext.detect(root).orElseThrow();

        assertThat(context.staged_files()).containsExactly("staged.txt");
    }

    @Test
    void render_formats_all_sections() {
        GitContext context = new GitContext(
                Optional.of("feat/test"),
                List.of(new GitCommitEntry("abc1234", "add feature"), new GitCommitEntry("def5678", "fix bug")),
                List.of("src/main.rs"));

        String rendered = context.render();

        assertThat(rendered).contains("Git branch: feat/test");
        assertThat(rendered).contains("abc1234 add feature");
        assertThat(rendered).contains("def5678 fix bug");
        assertThat(rendered).contains("src/main.rs");
    }

    @Test
    void render_omits_empty_sections() {
        GitContext context = new GitContext(Optional.of("main"), List.of(), List.of());

        String rendered = context.render();

        assertThat(rendered).contains("Git branch: main");
        assertThat(rendered).doesNotContain("Recent commits:");
        assertThat(rendered).doesNotContain("Staged files:");
    }

    @Test
    void limits_to_five_recent_commits(@TempDir Path root) throws Exception {
        git(root, "init", "--quiet", "--initial-branch=main");
        git(root, "config", "user.email", "tests@example.com");
        git(root, "config", "user.name", "Git Context Tests");
        for (int i = 1; i <= 8; i++) {
            String name = "file" + i + ".txt";
            Files.writeString(root.resolve(name), i + "\n");
            git(root, "add", name);
            git(root, "commit", "-m", "commit " + i, "--quiet");
        }

        GitContext context = GitContext.detect(root).orElseThrow();

        assertThat(context.recent_commits()).hasSize(5);
        assertThat(context.recent_commits().get(0).subject()).isEqualTo("commit 8");
        assertThat(context.recent_commits().get(4).subject()).isEqualTo("commit 4");
    }
}
